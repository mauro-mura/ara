package io.ara.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCategoryTest {

    @Test
    void isRetryable_trueOnlyForTransientServerSideStates() {
        assertTrue(ErrorCategory.RATE_LIMIT.isRetryable());
        assertTrue(ErrorCategory.SERVER_ERROR.isRetryable());
        // An empty completion is typically non-deterministic sampling noise, not a structural
        // problem with the request — the same endpoint can plausibly succeed on retry.
        assertTrue(ErrorCategory.EMPTY_RESPONSE.isRetryable());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCategory.class,
            names = {"RATE_LIMIT", "SERVER_ERROR", "EMPTY_RESPONSE"},
            mode = EnumSource.Mode.EXCLUDE)
    void isRetryable_falseForEverythingElse(ErrorCategory category) {
        assertFalse(category.isRetryable());
    }

    @Test
    void shouldFailover_trueForNetworkServerRateLimitQuotaTimeoutAndEmptyResponse() {
        assertTrue(ErrorCategory.NETWORK.shouldFailover());
        assertTrue(ErrorCategory.SERVER_ERROR.shouldFailover());
        assertTrue(ErrorCategory.RATE_LIMIT.shouldFailover());
        assertTrue(ErrorCategory.QUOTA_EXCEEDED.shouldFailover());
        // A different provider is not subject to the same load (TIMEOUT) and may not
        // reproduce a blank answer (EMPTY_RESPONSE) — see ErrorCategory's own javadoc.
        assertTrue(ErrorCategory.TIMEOUT.shouldFailover());
        assertTrue(ErrorCategory.EMPTY_RESPONSE.shouldFailover());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCategory.class,
            names = {"NETWORK", "SERVER_ERROR", "RATE_LIMIT", "QUOTA_EXCEEDED", "TIMEOUT", "EMPTY_RESPONSE"},
            mode = EnumSource.Mode.EXCLUDE)
    void shouldFailover_falseForNonRecoverableFailures(ErrorCategory category) {
        assertFalse(category.shouldFailover());
    }
}
