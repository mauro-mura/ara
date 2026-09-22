package io.ara.core.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * Contextual attributes of the current call — when it happens and how deep in a delegation
 * chain — independent of who is calling or being called (ADR-033).
 *
 * @param timestamp        wall-clock time of the decision; {@code io.ara.runtime.auth.policy.BusinessHoursPolicy}
 *                         reads this rather than {@code Instant.now()} so a decision is
 *                         reproducible and testable without mocking the clock
 * @param delegationDepth  how many delegation hops deep this call is (0 = a direct,
 *                         non-delegated invocation); {@code io.ara.runtime.auth.policy.DelegationDepthPolicy}
 *                         denies past a configured maximum
 * @param requestId        opaque correlation id for audit; typically {@code AgentTask.correlationId()}
 */
public record EnvironmentAttributes(
        Instant timestamp,
        int     delegationDepth,
        String  requestId
) {

    public EnvironmentAttributes {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (delegationDepth < 0) {
            throw new IllegalArgumentException("delegationDepth must be >= 0");
        }
    }
}
