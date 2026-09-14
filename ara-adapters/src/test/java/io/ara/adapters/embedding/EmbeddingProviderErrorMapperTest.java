package io.ara.adapters.embedding;

import java.io.IOException;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import io.ara.core.common.ErrorCategory;
import io.ara.core.memory.EmbeddingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors {@code io.ara.adapters.llm.ProviderErrorMapperTest} for the embedding mapper: same
 * langchain4j typed-exception hierarchy, same retryability decisions, {@link EmbeddingException}
 * instead of {@code LlmException}.
 */
class EmbeddingProviderErrorMapperTest {

    private static final String PROVIDER = "test-provider";

    @Test
    void an_invalid_request_is_non_retryable_and_keeps_the_provider() {
        EmbeddingException mapped = EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new InvalidRequestException("bad payload"));

        assertNotNull(mapped);
        assertFalse(mapped.isRetryable());
        assertFalse(mapped.shouldFailover());
        assertEquals(ErrorCategory.INVALID_REQUEST, mapped.category());
        assertEquals(PROVIDER, mapped.provider());
    }

    @Test
    void the_typed_exception_is_found_through_the_cause_chain() {
        Throwable wrapped = new RuntimeException("call failed",
                new InvalidRequestException("bad part", new HttpException(400, "bad part")));

        EmbeddingException mapped = EmbeddingProviderErrorMapper.fromTypedException(PROVIDER, wrapped);

        assertNotNull(mapped, "the classification must not depend on being the outermost throwable");
        assertFalse(mapped.isRetryable());
    }

    @Test
    void content_filtering_collapses_to_invalid_request() {
        // Unlike the chat mapper, embeddings have no dedicated content-filtered category —
        // see the class javadoc for why the two are behaviourally identical here.
        EmbeddingException mapped = EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new ContentFilteredException("blocked"));

        assertNotNull(mapped);
        assertEquals(ErrorCategory.INVALID_REQUEST, mapped.category());
        assertFalse(mapped.isRetryable());
        assertFalse(mapped.shouldFailover());
    }

    @Test
    void server_and_rate_limit_failures_stay_retryable_and_failover() {
        EmbeddingException rateLimit = EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new RateLimitException("slow down"));
        EmbeddingException serverError = EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new InternalServerException("boom"));

        assertTrue(rateLimit.isRetryable());
        assertTrue(rateLimit.shouldFailover());
        assertTrue(serverError.isRetryable());
        assertTrue(serverError.shouldFailover());
    }

    @Test
    void connection_failures_are_non_retryable_but_failover() {
        EmbeddingException timeout = EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new dev.langchain4j.exception.TimeoutException("too slow"));
        EmbeddingException retriable = EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new RetriableException("connection reset"));

        assertFalse(timeout.isRetryable());
        assertTrue(timeout.shouldFailover());
        assertFalse(retriable.isRetryable());
        assertTrue(retriable.shouldFailover());
    }

    @Test
    void non_retryable_failures_abort_failover() {
        assertFalse(EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new AuthenticationException("bad key")).shouldFailover());
        assertFalse(EmbeddingProviderErrorMapper.fromTypedException(
                PROVIDER, new ModelNotFoundException("no such model")).shouldFailover());
    }

    @Test
    void an_unrecognised_langchain4j_subclass_still_gets_the_right_retryability() {
        class FutureNonRetriable extends NonRetriableException {
            FutureNonRetriable() { super("something new"); }
        }
        class FutureRetriable extends RetriableException {
            FutureRetriable() { super("something new"); }
        }

        assertFalse(EmbeddingProviderErrorMapper.fromTypedException(PROVIDER, new FutureNonRetriable()).isRetryable());
        assertFalse(EmbeddingProviderErrorMapper.fromTypedException(PROVIDER, new FutureRetriable()).isRetryable());
    }

    @Test
    void a_plain_transport_failure_is_left_to_the_callers_own_fallback() {
        assertNull(EmbeddingProviderErrorMapper.fromTypedException(PROVIDER, new IOException("connection reset")));
    }

    @Test
    void a_cyclic_cause_chain_terminates() {
        RuntimeException first  = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> EmbeddingProviderErrorMapper.fromTypedException(PROVIDER, first));
    }

    // ── fallbackClassify ─────────────────────────────────────────────────────

    @Test
    void fallbackClassify_prefersTheTypedClassification() {
        EmbeddingException mapped = EmbeddingProviderErrorMapper.fallbackClassify(
                PROVIDER, "bad key", new AuthenticationException("bad key"));

        assertEquals(ErrorCategory.AUTHENTICATION, mapped.category());
    }

    @Test
    void fallbackClassify_reportsAConnectionError_forAnIOException() {
        EmbeddingException mapped = EmbeddingProviderErrorMapper.fallbackClassify(
                PROVIDER, "connection reset", new IOException("connection reset"));

        assertEquals(ErrorCategory.NETWORK, mapped.category());
        assertFalse(mapped.isRetryable(), "a connection failure is not worth retrying against the same host");
        assertTrue(mapped.shouldFailover());
    }

    @Test
    void fallbackClassify_reportsARetryableNetworkError_forAnUnclassifiedFailure() {
        EmbeddingException mapped = EmbeddingProviderErrorMapper.fallbackClassify(
                PROVIDER, "mystery", new RuntimeException("mystery"));

        assertEquals(ErrorCategory.NETWORK, mapped.category());
        assertTrue(mapped.isRetryable());
    }
}
