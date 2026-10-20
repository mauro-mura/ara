package io.ara.runtime.hitl;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.PendingApproval;
import io.ara.core.hitl.ApprovalDecision;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The decisions a resumed run already holds for the tool calls of the turn it stopped in
 * (ADR-048 D2), so {@link ApprovalToolRegistry} applies them instead of asking again.
 *
 * <p>Without this, completing the suspended turn would send every gated call back through the
 * gate, open a new request and stop on it forever. With it, the call is answered by the
 * decision the operator already gave, through the very same approve / modify / reject paths
 * as a decision that arrives while a thread waits — one behaviour, whichever way the answer
 * came.
 *
 * <p><b>Each decision is used once.</b> A verdict covers the call that was asked about, not the
 * tool: once the resumed turn has run, the model may call the same tool with the same arguments
 * again, and that call is a new action that needs its own approval. Calls are matched on tool
 * id and exact arguments, and a verdict is removed when taken; two identical calls in one turn
 * hold two verdicts.
 *
 * <p><b>Only for the task it was made for.</b> The task's {@code RunContext} is copied into
 * tasks it delegates, and a delegate calling the same tool with the same arguments must not
 * skip its own gate on the strength of its caller's answer.
 *
 * <p>Thread-safe: the calls of a turn are dispatched in parallel.
 */
public final class ApprovalVerdicts {

    /** The {@code RunContext} opaque key under which a resumed task carries its verdicts. */
    public static final String KEY = "io.ara.run.approvalVerdicts";

    private final String taskId;
    private final Map<String, ConcurrentLinkedQueue<ApprovalDecision>> byCall = new ConcurrentHashMap<>();

    private ApprovalVerdicts(String taskId) {
        this.taskId = taskId;
    }

    /**
     * The verdicts for the calls of {@code pending} that waited for approval, taken from
     * {@code decisions} by request id.
     */
    public static ApprovalVerdicts of(String taskId, PendingApproval pending, Map<String, ApprovalDecision> decisions) {
        ApprovalVerdicts verdicts = new ApprovalVerdicts(taskId);
        for (PendingApproval.ToolCall call : pending.calls()) {
            if (call.approval() != null) {
                ApprovalDecision decision = decisions.get(call.approval().requestId());
                if (decision == null) {
                    throw new IllegalArgumentException("no decision for approval request " + call.approval().requestId());
                }
                verdicts.byCall.computeIfAbsent(keyOf(call.toolId(), call.argumentJson()),
                        key -> new ConcurrentLinkedQueue<>()).add(decision);
            }
        }
        return verdicts;
    }

    /** A copy of {@code task} that carries these verdicts. */
    public AgentTask attachTo(AgentTask task) {
        return task.withAttachment(KEY, this);
    }

    /** The verdicts carried by {@code task}, or {@code null} when it has none. */
    static ApprovalVerdicts from(AgentTask task) {
        return task == null ? null : task.runContext().opaque(KEY, ApprovalVerdicts.class);
    }

    /** Whether a verdict is still held for this call of {@code task}, without using it. */
    boolean holds(AgentTask task, String toolId, String argumentJson) {
        ConcurrentLinkedQueue<ApprovalDecision> queue = byCall.get(keyOf(toolId, argumentJson));
        return task.taskId().equals(taskId) && queue != null && !queue.isEmpty();
    }

    /** Removes and returns the verdict for this call of {@code task}, if one is held. */
    Optional<ApprovalDecision> take(AgentTask task, String toolId, String argumentJson) {
        if (!task.taskId().equals(taskId)) {
            return Optional.empty();
        }
        ConcurrentLinkedQueue<ApprovalDecision> queue = byCall.get(keyOf(toolId, argumentJson));
        return queue == null ? Optional.empty() : Optional.ofNullable(queue.poll());
    }

    private static String keyOf(String toolId, String argumentJson) {
        return toolId + '\u0000' + argumentJson;
    }
}
