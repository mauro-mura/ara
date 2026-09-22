package io.ara.core.llm;

import io.ara.core.common.ErrorCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmException#shouldFailover()} now delegates to {@link LlmException#errorCategory()},
 * which reduces {@link LlmException.ErrorType} to the shared {@link ErrorCategory}. These
 * tests pin the mapping and confirm the delegation didn't change any existing behaviour.
 */
class LlmExceptionTest {

    @Test
    void connectionError_mapsToNetworkCategory_nonRetryableButFailsOver() {
        LlmException ex = LlmException.connectionError("openai", "connect timed out", null);

        assertEquals(ErrorCategory.NETWORK, ex.errorCategory());
        assertFalse(ex.isRetryable());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void networkError_mapsToNetworkCategory_retryableAndFailsOver() {
        LlmException ex = LlmException.networkError("openai", "transient", null);

        assertEquals(ErrorCategory.NETWORK, ex.errorCategory());
        assertTrue(ex.isRetryable());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void timeout_mapsToTimeoutCategory_nonRetryableButFailsOver() {
        LlmException ex = LlmException.timeout("openai", "request timed out", null);

        assertEquals(ErrorCategory.TIMEOUT, ex.errorCategory());
        assertFalse(ex.isRetryable());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void emptyResponse_mapsToEmptyResponseCategory_retryableAndFailsOver() {
        LlmException ex = LlmException.emptyResponse("openai", "empty completion");

        assertEquals(ErrorCategory.EMPTY_RESPONSE, ex.errorCategory());
        assertTrue(ex.isRetryable());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void rateLimit_mapsToRateLimitCategory() {
        LlmException ex = LlmException.rateLimit("openai", "429");
        assertEquals(ErrorCategory.RATE_LIMIT, ex.errorCategory());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void serverError_mapsToServerErrorCategory() {
        LlmException ex = LlmException.serverError("openai", "boom", 503);
        assertEquals(ErrorCategory.SERVER_ERROR, ex.errorCategory());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void authenticationError_abortsFailover() {
        LlmException ex = LlmException.authenticationError("openai", "bad key");
        assertEquals(ErrorCategory.AUTHENTICATION, ex.errorCategory());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void invalidRequest_abortsFailover() {
        LlmException ex = LlmException.invalidRequest("openai", "bad payload");
        assertEquals(ErrorCategory.INVALID_REQUEST, ex.errorCategory());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void contextLengthExceeded_mapsToInvalidRequestCategory_abortsFailover() {
        LlmException ex = LlmException.contextLengthExceeded("openai", "gpt-4o", 200_000, 128_000);
        assertEquals(ErrorCategory.INVALID_REQUEST, ex.errorCategory());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void contentFiltered_abortsFailover() {
        LlmException ex = LlmException.contentFiltered("openai", "blocked");
        assertEquals(ErrorCategory.CONTENT_FILTERED, ex.errorCategory());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void modelNotFound_abortsFailover() {
        LlmException ex = LlmException.modelNotFound("openai", "no-such-model");
        assertEquals(ErrorCategory.MODEL_NOT_FOUND, ex.errorCategory());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void unsupportedMediaType_abortsFailover() {
        LlmException ex = LlmException.unsupportedMediaType("openai", "application/pdf", "doc.pdf",
                java.util.Set.of("image/png"));
        assertEquals(ErrorCategory.UNSUPPORTED_OPERATION, ex.errorCategory());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void unknown_abortsFailover() {
        LlmException ex = new LlmException("oops");
        assertEquals(ErrorCategory.UNKNOWN, ex.errorCategory());
        assertFalse(ex.shouldFailover());
    }
}
