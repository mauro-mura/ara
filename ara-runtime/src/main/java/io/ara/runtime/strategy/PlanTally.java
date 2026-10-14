package io.ara.runtime.strategy;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.llm.LlmCompletion;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-run accumulators: iteration/token tallies, the execution trace and the notes the
 * steps leave for each other. Replaces the previous {@code int[] iters = {0}}
 * single-element-array idiom — a named object mutated in place says what it is; a one-slot array
 * only says how it was smuggled past Java's by-value parameters.
 *
 * <p>Not thread-safe by itself; confine one instance to one thread. A parallel step batch
 * ({@code PlanExecuteStrategy.executeBatch}) gives each worker its own tally via
 * {@link #copyForWorker()} rather than sharing this one, and merges them back into the parent
 * — in batch (plan) order, never completion order — via {@link #mergeFrom}, once the whole
 * batch has finished. This mirrors {@code RunEvents}'s own documented invariant for parallel
 * tool dispatch ("results are collected and recorded after all of them finish"): a worker tally
 * never calls {@link #record}, so no {@code ExecutionStep} it accumulates is ever announced to
 * the run's event listener from the worker's own thread, out of plan order, or out of turn with
 * a sibling worker's steps. {@link #mergeFrom} is what actually calls {@code RunEvents.record}
 * for each of them, on the thread driving the plan, in the order the worker produced them.
 */
final class PlanTally {
    int iterations;
    int promptTokens;
    int outputTokens;
    final List<ExecutionStep> steps = new ArrayList<>();
    /**
     * Facts the steps left for the steps after them, in the order they were left. Never
     * truncated: a fact cut in half is worse than none.
     */
    final List<String> notes = new ArrayList<>();
    private final AgentTask task;
    /**
     * {@code null} on the tally that drives the plan; set on a worker's own copy, which
     * accumulates steps locally ({@link #recordLocal}) instead of announcing them — see the
     * class javadoc.
     */
    private final boolean isWorkerCopy;

    PlanTally(AgentTask task) {
        this.task = task;
        this.isWorkerCopy = false;
    }

    private PlanTally(AgentTask task, int iterations, int promptTokens, int outputTokens) {
        this.task = task;
        this.isWorkerCopy = true;
        this.iterations = iterations;
        this.promptTokens = promptTokens;
        this.outputTokens = outputTokens;
    }

    /**
     * A fresh tally for one worker of a parallel step batch, seeded with this tally's running
     * totals so iteration/token/budget accounting carries over into the step the worker is
     * about to run. Carries no steps or notes of its own yet — those start empty and are merged
     * back by {@link #mergeFrom} once the batch is done.
     */
    PlanTally copyForWorker() {
        return new PlanTally(task, iterations, promptTokens, outputTokens);
    }

    /**
     * Folds a worker's local totals, steps and notes into this (parent) tally, announcing each
     * step to the run's listener in the order the worker recorded them — the one place a
     * worker's {@code ExecutionStep}s are actually emitted, from the thread driving the plan,
     * not the worker's own. Call once per worker, in batch order, after the whole batch (every
     * worker) has finished — folding one worker while a sibling is still running would let the
     * same race {@link #record} is written to prevent back in through the parent's own fields.
     */
    void mergeFrom(PlanTally worker) {
        iterations = worker.iterations;
        promptTokens = worker.promptTokens;
        outputTokens = worker.outputTokens;
        budgetFailure = worker.budgetFailure != null ? worker.budgetFailure : budgetFailure;
        notes.addAll(worker.notes);
        worker.steps.forEach(this::record);
    }

    /** The one place a step is recorded: adds it to {@link #steps} and announces it to the run's listener. */
    void record(ExecutionStep step) {
        if (isWorkerCopy) {
            // A worker's own thread must never announce a step directly: see the class javadoc.
            // mergeFrom replays this same list through the parent's record(...) once the batch
            // is done, which is where the announcement actually happens.
            steps.add(step);
        } else {
            RunEvents.record(task, steps, step);
        }
    }

    // replan() returns a step list, not an ExecutionResult, so a budget breach detected
    // there is parked here for the caller to return — keeps the failure reason honest
    // ("budget exceeded") instead of masquerading as "produced no result".
    ExecutionResult budgetFailure;

    void addUsage(LlmCompletion completion) {
        promptTokens += completion.promptTokens();
        outputTokens += completion.outputTokens();
    }

    /** A failed result carrying everything tallied so far. */
    ExecutionResult fail(String reason) {
        return ExecutionResult.failure(reason, iterations, promptTokens, outputTokens, steps);
    }
}
