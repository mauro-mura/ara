package io.ara.core.hitl;

/**
 * SPI for notifying an external observer that a human approval request is pending.
 *
 * <p>Implementations send an asynchronous, fire-and-forget notification (webhook,
 * log entry, etc.) without blocking the agent. Failures must be handled internally
 * and logged rather than propagated.
 *
 * @see ApprovalRequest
 */
public interface ApprovalNotifier {

    /**
     * Sends a notification that {@code request} is awaiting human approval.
     *
     * <p>Must not throw. Transient failures should be caught and logged internally.
     *
     * @param request the pending approval request; never {@code null}
     */
    void notify(ApprovalRequest request);
}
