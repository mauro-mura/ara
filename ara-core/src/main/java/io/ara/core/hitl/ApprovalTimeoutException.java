package io.ara.core.hitl;

import io.ara.core.exceptions.AraException;

import java.time.Instant;

/**
 * Thrown when no human decision is submitted before an {@link ApprovalRequest} expires.
 *
 * @see ApprovalGate
 * @see ApprovalRequest
 */
public class ApprovalTimeoutException extends AraException {

    private static final long serialVersionUID = 2028079525569400657L;
	private final String requestId;
    private final Instant expiresAt;

    public ApprovalTimeoutException(String requestId, Instant expiresAt) {
        super("Approval request timed out: requestId=" + requestId + ", expiresAt=" + expiresAt);
        this.requestId = requestId;
        this.expiresAt = expiresAt;
    }

    public ApprovalTimeoutException(ApprovalRequest request) {
        this(request.requestId(), request.expiresAt());
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
