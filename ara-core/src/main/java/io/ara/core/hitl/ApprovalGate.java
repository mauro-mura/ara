package io.ara.core.hitl;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Suspends the calling virtual thread until a human decision is received or the
 * request times out.
 *
 * <p>Implementations register the pending request and return a
 * {@link CompletableFuture} that is completed by the operator when the external
 * decision arrives. The calling agent typically blocks via {@code future.join()},
 * which is cheap on a virtual thread (Project Loom).
 *
 * <p>Example:
 * <pre>{@code
 * ApprovalRequest req = ApprovalRequest.of(agentId, "delete-record", payload, Duration.ofMinutes(10));
 * ApprovalDecision decision = gate.requestApproval(req).join(); // virtual thread parks here
 * switch (decision) {
 *     case ApprovalDecision.Approved a  -> proceed();
 *     case ApprovalDecision.Rejected r  -> abort(r.reason());
 *     case ApprovalDecision.Modified m  -> proceed(m.newPayload());
 * }
 * }</pre>
 *
 * @see ApprovalDecision
 * @see ApprovalRequest
 * @see ApprovalTimeoutException
 */
public interface ApprovalGate {

    /**
     * Registers the approval request and returns a future that resolves to the human decision.
     *
     * <p>The future completes exceptionally with {@link ApprovalTimeoutException}
     * if no decision is submitted before {@link ApprovalRequest#expiresAt()}.
     *
     * @param request the approval request; never {@code null}
     * @return a future that resolves to an {@link ApprovalDecision}; never {@code null}
     */
    CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request);

    /**
     * Submits a human decision for a pending approval request.
     *
     * <p>If the future for {@code requestId} has already completed (e.g. timed out),
     * this call is a silent no-op.
     *
     * @param requestId the UUID of the pending request; never {@code null}
     * @param decision  the human decision; never {@code null}
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    void submit(String requestId, ApprovalDecision decision);

    /**
     * Returns a snapshot of approval requests that are still pending.
     *
     * @return immutable list of pending {@link ApprovalRequest}s
     */
    List<ApprovalRequest> getPendingRequests();
}
