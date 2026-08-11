package io.ara.core.hitl;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable description of a human approval request.
 *
 * <p>Create via the factory method:
 * <pre>{@code
 * ApprovalRequest req = ApprovalRequest.of("payment-agent", "process-payment",
 *                                          paymentPayload, Duration.ofMinutes(30));
 * }</pre>
 *
 * @param requestId  unique identifier (UUID), generated automatically by {@link #of}
 * @param agentId    identifier of the agent requesting approval
 * @param action     human-readable name of the action to approve
 * @param payload    action data
 * @param expiresAt  absolute instant after which the request times out
 * @param metadata   optional key-value pairs for contextual information
 */
public record ApprovalRequest(
        String requestId,
        String agentId,
        String action,
        Object payload,
        Instant expiresAt,
        Map<String, Object> metadata
) {

    public ApprovalRequest {
        if (requestId == null || requestId.isBlank())
            throw new IllegalArgumentException("requestId must not be null or blank");
        if (agentId == null || agentId.isBlank())
            throw new IllegalArgumentException("agentId must not be null or blank");
        if (action == null || action.isBlank())
            throw new IllegalArgumentException("action must not be null or blank");
        if (expiresAt == null)
            throw new IllegalArgumentException("expiresAt must not be null");
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    /**
     * Creates a new {@code ApprovalRequest} with a generated UUID and expiry
     * computed from {@code timeout}.
     */
    public static ApprovalRequest of(String agentId, String action, Object payload, Duration timeout) {
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                agentId,
                action,
                payload,
                Instant.now().plus(timeout),
                Collections.emptyMap()
        );
    }

    /**
     * Creates a new {@code ApprovalRequest} with metadata.
     */
    public static ApprovalRequest of(String agentId, String action, Object payload,
                                     Duration timeout, Map<String, Object> metadata) {
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                agentId,
                action,
                payload,
                Instant.now().plus(timeout),
                metadata
        );
    }
}
