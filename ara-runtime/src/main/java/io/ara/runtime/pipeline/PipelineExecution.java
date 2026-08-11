package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunState;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of an {@link AgentPipeline} execution passed to route functions
 * and, since input shaping was added, to per-step input builders too.
 *
 * <p>Carries the original {@link AgentTask} (not just its input string) so a step's
 * {@link PipelineStep#input()} shaper can read {@link AgentTask#runContext()} — in
 * particular {@link #state()} — not only {@link #lastOutput()}.
 *
 * @param task      the original task passed to {@link AgentPipeline#run(AgentTask)}
 * @param history   results of all steps executed so far, in execution order
 * @param stepCount total number of step executions so far (including retries)
 */
public record PipelineExecution(
        AgentTask        task,
        List<StepResult> history,
        int              stepCount
) {

    public PipelineExecution {
        Objects.requireNonNull(task,    "task must not be null");
        Objects.requireNonNull(history, "history must not be null");
        history = List.copyOf(history);
    }

    /** The original input passed to the pipeline — {@code task.input()}. */
    public String initialInput() {
        return task.input();
    }

    /**
     * The output of the most recently executed step, or the original input if no
     * step has run yet.
     */
    public String lastOutput() {
        return history.isEmpty() ? task.input() : history.getLast().output();
    }

    /**
     * This pipeline run's shared {@link RunState} — the same instance for every step,
     * since every step's task derives from the same {@link #task()} (SHARED by
     * construction, unlike LLM-driven delegation which is a separate, configurable axis).
     */
    public RunState state() {
        return task.runContext().state();
    }

    /**
     * Returns the most recent result for the given step name, if present.
     * Searches from the end of history so retried steps return the latest run.
     */
    public Optional<StepResult> resultOf(String stepName) {
        Objects.requireNonNull(stepName, "stepName must not be null");
        return history.reversed().stream()
                .filter(r -> r.stepName().equals(stepName))
                .findFirst();
    }

    /**
     * Result produced by a single step execution.
     *
     * @param stepName name of the step as declared in the pipeline builder
     * @param output   the post-processed output string returned by the agent
     * @param elapsed  wall-clock duration of that step execution
     */
    public record StepResult(String stepName, String output, Duration elapsed) {
        public StepResult {
            Objects.requireNonNull(stepName, "stepName must not be null");
            Objects.requireNonNull(output,   "output must not be null");
            Objects.requireNonNull(elapsed,  "elapsed must not be null");
        }
    }
}