package io.ara.adapters.llm;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmException.ErrorType;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Retryability is the one field of {@link LlmException} the runtime acts on, so every case here
 * asserts it explicitly rather than only the {@code errorType}.
 *
 * <p>The case that motivated this class: a malformed request used to fall through to
 * {@code networkError} — retryable — so the strategy retried it and a failover pool then tried
 * it against every fallback, for a request no endpoint could have accepted.
 */
class ProviderErrorMapperTest {

    private static final String PROVIDER = "test-provider";

    @Test
    void an_invalid_request_is_non_retryable_and_keeps_the_provider() {
        LlmException mapped = ProviderErrorMapper.fromTypedException(
                PROVIDER, new InvalidRequestException("Unknown part type: file None"));

        assertNotNull(mapped);
        assertFalse(mapped.isRetryable(), "a 400 cannot become a 200 by being sent again");
        assertEquals(ErrorType.INVALID_REQUEST, mapped.errorType());
        assertEquals(PROVIDER, mapped.provider());
        assertTrue(mapped.getMessage().contains("Unknown part type"), mapped.getMessage());
    }

    @Test
    void the_typed_exception_is_found_through_the_cause_chain() {
        // The shape langchain4j actually produces: the typed exception wraps an HttpException,
        // and adapters catch whatever their HTTP layer wrapped it in.
        Throwable wrapped = new RuntimeException("call failed",
                new InvalidRequestException("bad part", new HttpException(400, "bad part")));

        LlmException mapped = ProviderErrorMapper.fromTypedException(PROVIDER, wrapped);

        assertNotNull(mapped, "the classification must not depend on being the outermost throwable");
        assertFalse(mapped.isRetryable());
        assertEquals(ErrorType.INVALID_REQUEST, mapped.errorType());
    }

    @Test
    void content_filtering_is_reported_as_itself_not_as_a_malformed_request() {
        // ContentFilteredException extends InvalidRequestException, so order matters: reporting
        // a refusal as a malformed request sends the reader hunting for a bug in their payload.
        LlmException mapped = ProviderErrorMapper.fromTypedException(
                PROVIDER, new ContentFilteredException("blocked"));

        assertNotNull(mapped);
        assertEquals(ErrorType.CONTENT_FILTERED, mapped.errorType());
        assertFalse(mapped.isRetryable());
    }

    @Test
    void retryable_failures_stay_retryable() {
        assertTrue(ProviderErrorMapper.fromTypedException(PROVIDER,
                new RateLimitException("slow down")).isRetryable());
        assertTrue(ProviderErrorMapper.fromTypedException(PROVIDER,
                new InternalServerException("boom")).isRetryable());
        assertTrue(ProviderErrorMapper.fromTypedException(PROVIDER,
                new dev.langchain4j.exception.TimeoutException("too slow")).isRetryable());
    }

    @Test
    void non_retryable_failures_stay_non_retryable() {
        assertFalse(ProviderErrorMapper.fromTypedException(PROVIDER,
                new AuthenticationException("bad key")).isRetryable());
        assertFalse(ProviderErrorMapper.fromTypedException(PROVIDER,
                new ModelNotFoundException("no such model")).isRetryable());
    }

    @Test
    void an_unrecognised_langchain4j_subclass_still_gets_the_right_retryability() {
        // The catch-all on the two base classes: a library upgrade that adds a subclass must
        // not be able to silently turn a non-retriable failure into a retryable one.
        class FutureNonRetriable extends NonRetriableException {
            FutureNonRetriable() { super("something new"); }
        }
        class FutureRetriable extends RetriableException {
            FutureRetriable() { super("something new"); }
        }

        assertFalse(ProviderErrorMapper.fromTypedException(PROVIDER, new FutureNonRetriable()).isRetryable());
        assertTrue(ProviderErrorMapper.fromTypedException(PROVIDER, new FutureRetriable()).isRetryable());
    }

    @Test
    void a_plain_transport_failure_is_left_to_the_callers_own_fallback() {
        assertNull(ProviderErrorMapper.fromTypedException(PROVIDER, new IOException("connection reset")),
                "an untyped failure must not be classified here — that is the adapter's job");
    }

    @Test
    void a_cyclic_cause_chain_terminates() {
        // Reachable, not hypothetical: Throwable.initCause rejects only *self*-causation, so
        // two exceptions can be made to cause each other. An unbounded walk would hang here.
        RuntimeException first  = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> ProviderErrorMapper.fromTypedException(PROVIDER, first),
                "the cause walk must be bounded");
    }

    @Test
    void a_typed_exception_buried_deeper_than_the_bound_is_not_found() {
        // The bound's honest consequence, stated rather than discovered: past it the adapter's
        // own fallback decides. Sixteen frames is far beyond any real adapter chain.
        Throwable deep = new InvalidRequestException("bad part");
        for (int i = 0; i < 20; i++) {
            deep = new RuntimeException("wrapper " + i, deep);
        }
        assertNull(ProviderErrorMapper.fromTypedException(PROVIDER, deep));
    }
}
