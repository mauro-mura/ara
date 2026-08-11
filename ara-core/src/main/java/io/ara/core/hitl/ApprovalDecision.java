package io.ara.core.hitl;

/**
 * Outcome of a human approval request.
 *
 * <p>Three mutually exclusive variants enable exhaustive {@code switch} expressions
 * with full compiler enforcement (Java 21+):
 *
 * <pre>{@code
 * ApprovalDecision decision = gate.requestApproval(request).join();
 * switch (decision) {
 *     case ApprovalDecision.Approved a  -> executeAction(request.payload());
 *     case ApprovalDecision.Rejected r  -> log.warn("Rejected: {}", r.reason());
 *     case ApprovalDecision.Modified m  -> executeAction(m.newPayload());
 * }
 * }</pre>
 *
 * @see ApprovalGate
 * @see ApprovalRequest
 */
public sealed interface ApprovalDecision
        permits ApprovalDecision.Approved,
                ApprovalDecision.Rejected,
                ApprovalDecision.Modified {

    /** The action is approved; the agent proceeds with the original payload. */
    record Approved() implements ApprovalDecision {}

    /**
     * The action is rejected; the agent must not execute it.
     *
     * @param reason human-readable explanation; never {@code null}
     */
    record Rejected(String reason) implements ApprovalDecision {
        public Rejected {
            if (reason == null) throw new IllegalArgumentException("reason must not be null");
        }
    }

    /**
     * The action is approved with a modified payload; the agent executes with
     * {@link #newPayload()} instead of the original.
     *
     * @param newPayload the revised action payload; never {@code null}
     */
    record Modified(Object newPayload) implements ApprovalDecision {
        public Modified {
            if (newPayload == null) throw new IllegalArgumentException("newPayload must not be null");
        }
    }
}
