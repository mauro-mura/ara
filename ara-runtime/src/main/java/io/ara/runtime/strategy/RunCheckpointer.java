package io.ara.runtime.strategy;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.PendingApproval;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.agent.SessionId;
import io.ara.core.common.Money;
import io.ara.core.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Writes one run's {@link RunCheckpoint} to the runtime's {@link CheckpointStore} (ADR-048 D2).
 *
 * <p><b>How it reaches the loop.</b> {@code AgentInstance} creates one per run and attaches it
 * to the task's {@code RunContext}, the same way it does {@link RunEvents}: a strategy's
 * {@code execute(...)} signature has no room for a runtime-wide collaborator, and widening it
 * would change a public interface that third-party strategies implement. The context's opaque
 * channel is the existing route for exactly this. Alternatives rejected: a store field on each
 * strategy (strategies are shared singletons, the store belongs to a runtime), and a new
 * parameter on {@code ExecutionStrategy.execute} (breaks every implementation for a feature
 * most do not use).
 *
 * <p>Replaced by every run, never inherited: a delegated run checkpoints under its own task id,
 * not its caller's.
 *
 * <p>A store that fails must not fail the run. The checkpoint is a safety net for a run that
 * would otherwise be lost, so losing the net is worth a warning, not worth losing the run the
 * net was meant to protect.
 *
 * <p>Thread-safe: holds only immutable values and the store.
 */
public final class RunCheckpointer {

    private static final Logger log = LoggerFactory.getLogger(RunCheckpointer.class);

    /** The {@code RunContext} opaque key under which a run's checkpointer is found. */
    public static final String KEY = "io.ara.run.checkpointer";

    private final CheckpointStore store;
    private final SessionId sessionId;
    private final String taskId;
    private final String input;
    private final boolean suspendable;
    private final BiFunction<Integer, Integer, Money> pricing;

    /**
     * @param suspendable whether the strategy driving the run can be resumed from a checkpoint
     *                    (see {@code ExecutionStrategy.resumable()}); only then may the run stop
     *                    to wait for an approval instead of blocking a thread for it
     * @param pricing prices a (prompt tokens, output tokens) pair with the running agent's own
     *                rates — the checkpoint carries the money spent so far, which only the
     *                agent's configuration can compute
     */
    public RunCheckpointer(CheckpointStore store, SessionId sessionId, String taskId, String input,
                           boolean suspendable, BiFunction<Integer, Integer, Money> pricing) {
        this.store     = Objects.requireNonNull(store, "store must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.taskId    = Objects.requireNonNull(taskId, "taskId must not be null");
        this.input     = Objects.requireNonNull(input, "input must not be null");
        this.suspendable = suspendable;
        this.pricing   = Objects.requireNonNull(pricing, "pricing must not be null");
    }

    /** A copy of {@code task} that carries this checkpointer, replacing any it inherited. */
    public AgentTask attachTo(AgentTask task) {
        return task.withAttachment(KEY, this);
    }

    /** The checkpointer attached to {@code task}, or {@code null} when the run is not checkpointed. */
    static RunCheckpointer from(AgentTask task) {
        return task.runContext().opaque(KEY, RunCheckpointer.class);
    }

    /**
     * Stores the state of a run that has just completed {@code iteration}, replacing the
     * previous checkpoint of the same run.
     */
    void save(int iteration, List<MemoryEntry> workingMemory, List<ExecutionStep> steps,
              int promptTokens, int outputTokens) {
        write(iteration, workingMemory, steps, promptTokens, outputTokens, null);
    }

    /** Whether this run may stop for an approval: a store is configured and the strategy can resume. */
    boolean canSuspend() {
        return suspendable;
    }

    /**
     * Stores the run as suspended before the calls of {@code pending}, which have not been
     * executed. Unlike {@link #save} this reports whether it worked: a run is only allowed to
     * stop if the place to resume it from was really written, otherwise the caller carries on
     * and waits for the approval the old way rather than lose the run.
     *
     * @return {@code true} when the checkpoint is stored
     */
    boolean saveSuspended(int iteration, List<MemoryEntry> workingMemory, List<ExecutionStep> steps,
                          int promptTokens, int outputTokens, PendingApproval pending) {
        return write(iteration, workingMemory, steps, promptTokens, outputTokens,
                Objects.requireNonNull(pending, "pending must not be null"));
    }

    private boolean write(int iteration, List<MemoryEntry> workingMemory, List<ExecutionStep> steps,
                          int promptTokens, int outputTokens, PendingApproval pending) {
        try {
            store.save(new RunCheckpoint(sessionId, taskId, input, iteration,
                    ReactExecutionSupport.toMessages(workingMemory), steps,
                    promptTokens, outputTokens, pricing.apply(promptTokens, outputTokens), pending));
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not checkpoint task [{}] at iteration {}: {}", taskId, iteration, e.getMessage());
            log.debug("Checkpoint failure detail", e);
            return false;
        }
    }

    /** Removes this run's checkpoint — called once the run has ended and nothing is left to resume. */
    public void clear() {
        try {
            store.delete(sessionId, taskId);
        } catch (RuntimeException e) {
            log.warn("Could not clear the checkpoint of task [{}]: {}", taskId, e.getMessage());
            log.debug("Checkpoint failure detail", e);
        }
    }
}
