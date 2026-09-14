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
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCategory.class, names = {"RATE_LIMIT", "SERVER_ERROR"}, mode = EnumSource.Mode.EXCLUDE)
    void isRetryable_falseForEverythingElse(ErrorCategory category) {
        assertFalse(category.isRetryable());
    }

    @Test
    void shouldFailover_trueForNetworkServerRateLimitAndQuota() {
        assertTrue(ErrorCategory.NETWORK.shouldFailover());
        assertTrue(ErrorCategory.SERVER_ERROR.shouldFailover());
        assertTrue(ErrorCategory.RATE_LIMIT.shouldFailover());
        assertTrue(ErrorCategory.QUOTA_EXCEEDED.shouldFailover());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCategory.class,
            names = {"NETWORK", "SERVER_ERROR", "RATE_LIMIT", "QUOTA_EXCEEDED"},
            mode = EnumSource.Mode.EXCLUDE)
    void shouldFailover_falseForNonRecoverableFailures(ErrorCategory category) {
        assertFalse(category.shouldFailover());
    }
}
