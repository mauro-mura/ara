package io.ara.core.agent;

import io.ara.core.common.Money;
import io.ara.core.llm.LlmMessage;

import java.util.List;
import java.util.Objects;

/**
 * Everything needed to continue a run from the point where it was suspended, instead of
 * from the start — the datum ADR-048 D2 names as missing.
 *
 * <p>A checkpoint is a plain, whole snapshot of a run at an iteration boundary: the same
 * point where the strategy loop already checks cancellation, deadline and budget. It holds
 * the run's own state only. Nothing that can be rebuilt from configuration (the system
 * prompt, the resolved tools) is stored, so a checkpoint stays small and does not go stale
 * when an agent is reconfigured between suspension and resume.
 *
 * <p>The record holds only strings, numbers, lists and other records, so a durable {@link
 * CheckpointStore} can choose its own storage format without this type taking a position on
 * it. The one exception is the {@link java.time.Instant} inside a pending {@code
 * ApprovalRequest}, which needs a mapper that knows {@code java.time} — that is the store's
 * concern, not this type's.
 *
 * @param sessionId        the session the run belongs to
 * @param taskId           the task being executed; with {@code sessionId} it identifies the run
 * @param input            the task's input text, so a run can be resumed from the checkpoint alone
 * @param iteration        the number of iterations already completed; a resumed run continues
 *                         from the next one
 * @param workingMemory    the conversation sent to the model so far, oldest first
 * @param steps            the execution trace accumulated so far
 * @param promptTokens     prompt tokens consumed so far, carried over so budgets keep counting
 * @param outputTokens     completion tokens consumed so far
 * @param cost             the money spent so far
 * @param pendingApproval  set when the run was suspended before a tool call a human must approve:
 *                         what it is waiting for, and the turn's calls to run once it is decided.
 *                         {@code null} for a checkpoint taken at an ordinary iteration boundary
 */
public record RunCheckpoint(
        SessionId sessionId,
        String taskId,
        String input,
        int iteration,
        List<LlmMessage> workingMemory,
        List<ExecutionStep> steps,
        int promptTokens,
        int outputTokens,
        Money cost,
        PendingApproval pendingApproval
) {

    /** A checkpoint taken at an ordinary iteration boundary: nothing is pending. */
    public RunCheckpoint(SessionId sessionId, String taskId, String input, int iteration,
                         List<LlmMessage> workingMemory, List<ExecutionStep> steps,
                         int promptTokens, int outputTokens, Money cost) {
        this(sessionId, taskId, input, iteration, workingMemory, steps, promptTokens, outputTokens, cost, null);
    }

    public RunCheckpoint {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        Objects.requireNonNull(input, "input must not be null");
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must be >= 0, got: " + iteration);
        }
        if (promptTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException(
                    "token counts must be >= 0, got prompt=" + promptTokens + " output=" + outputTokens);
        }
        Objects.requireNonNull(cost, "cost must not be null");
        workingMemory = workingMemory != null ? List.copyOf(workingMemory) : List.of();
        steps = steps != null ? List.copyOf(steps) : List.of();
    }
}
