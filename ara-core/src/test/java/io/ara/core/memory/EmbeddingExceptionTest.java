package io.ara.core.memory;

import io.ara.core.common.ErrorCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingExceptionTest {

    @Test
    void connectionError_isNonRetryableButFailsOver() {
        EmbeddingException ex = EmbeddingException.connectionError("openai", "connect timed out", null);

        assertEquals(ErrorCategory.NETWORK, ex.category());
        assertFalse(ex.isRetryable());
        assertTrue(ex.shouldFailover());
        assertEquals("openai", ex.provider());
    }

    @Test
    void networkError_isRetryableAndFailsOver() {
        EmbeddingException ex = EmbeddingException.networkError("ollama", "transient blip", null);

        assertTrue(ex.isRetryable());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void rateLimit_isRetryableAndFailsOver() {
        EmbeddingException ex = EmbeddingException.rateLimit("openai", "429");

        assertEquals(ErrorCategory.RATE_LIMIT, ex.category());
        assertTrue(ex.isRetryable());
        assertTrue(ex.shouldFailover());
        assertEquals(429, ex.statusCode());
    }

    @Test
    void serverError_isRetryableAndFailsOver() {
        EmbeddingException ex = EmbeddingException.serverError("mistral", "boom", 503);

        assertTrue(ex.isRetryable());
        assertTrue(ex.shouldFailover());
        assertEquals(503, ex.statusCode());
    }

    @Test
    void authenticationError_abortsFailover() {
        EmbeddingException ex = EmbeddingException.authenticationError("openai", "invalid api key");

        assertFalse(ex.isRetryable());
        assertFalse(ex.shouldFailover());
        assertEquals(401, ex.statusCode());
    }

    @Test
    void invalidRequest_abortsFailover() {
        EmbeddingException ex = EmbeddingException.invalidRequest("openai", "bad payload");

        assertFalse(ex.isRetryable());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void modelNotFound_abortsFailover() {
        EmbeddingException ex = EmbeddingException.modelNotFound("openai", "text-embedding-none");

        assertEquals(ErrorCategory.MODEL_NOT_FOUND, ex.category());
        assertFalse(ex.shouldFailover());
        assertTrue(ex.getMessage().contains("text-embedding-none"));
    }

    @Test
    void defaultConstructor_isUnknownAndNonFailover() {
        EmbeddingException ex = new EmbeddingException("oops");

        assertEquals(ErrorCategory.UNKNOWN, ex.category());
        assertFalse(ex.isRetryable());
        assertFalse(ex.shouldFailover());
    }
}
