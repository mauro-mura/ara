package io.ara.core.agent;

import io.ara.core.hitl.ApprovalRequest;

import java.util.List;
import java.util.Objects;

/**
 * What a suspended run is waiting for: the tool calls of the turn that was about to run, and
 * for each one that needs it, the approval a human must give (ADR-048 D2).
 *
 * <p>The whole turn is kept, not only the calls that need approval. The model asked for these
 * calls together, none has been executed, and a run resumed after the decisions must run them
 * as one turn — the calls that needed no approval included — or the observations the model
 * gets back would no longer line up with what it asked for. Every call that needs approval
 * gets its own request, all opened together: asking one at a time would force the checkpoint
 * to remember the decisions already given, and hide from the operator what the model asked for
 * in a single breath.
 *
 * @param calls every tool call of the suspended turn, in the order the model emitted them
 */
public record PendingApproval(List<ToolCall> calls) {

    /**
     * One tool call of the suspended turn.
     *
     * @param toolId       the tool to call
     * @param argumentJson the arguments the model gave
     * @param toolCallId   the provider's id for this call, or {@code null} when it gave none
     * @param approval     the approval this call waits for, or {@code null} when it needs none
     */
    public record ToolCall(String toolId, String argumentJson, String toolCallId, ApprovalRequest approval) {

        public ToolCall {
            Objects.requireNonNull(toolId, "toolId must not be null");
            Objects.requireNonNull(argumentJson, "argumentJson must not be null");
        }
    }

    public PendingApproval {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("a pending approval needs at least one tool call");
        }
        calls = List.copyOf(calls);
        if (calls.stream().noneMatch(call -> call.approval() != null)) {
            throw new IllegalArgumentException("a pending approval needs at least one call waiting for approval");
        }
    }

    /** The approvals being waited for, in call order. */
    public List<ApprovalRequest> requests() {
        return calls.stream().map(ToolCall::approval).filter(Objects::nonNull).toList();
    }
}
