package io.ara.core.memory;

import io.ara.core.common.ErrorCategory;

/**
 * Exception thrown during embedding operations.
 *
 * <p>Mirrors {@code LlmException}'s shape — a typed {@link ErrorCategory}, provider,
 * status code, retryability — so that a caller composing both chat and embedding clients
 * (e.g. {@code FailoverEmbeddingClient}) can reason about failures from either the same
 * way. Kept as its own type rather than reusing {@code LlmException} because the two ports
 * are otherwise independent ({@link EmbeddingClient} knows nothing about {@code LlmClient})
 * and a shared exception type would create a compile-time dependency between them for no
 * behavioural gain — the categories, not the class, are what needs to be shared.
 */
public class EmbeddingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCategory category;
    private final String        provider;
    private final Integer       statusCode;
    private final boolean       retryable;

    public EmbeddingException(String message) {
        this(message, null, ErrorCategory.UNKNOWN, null, null, false);
    }

    public EmbeddingException(String message, Throwable cause) {
        this(message, cause, ErrorCategory.UNKNOWN, null, null, false);
    }

    public EmbeddingException(String message, Throwable cause, ErrorCategory category,
                              String provider, Integer statusCode, boolean retryable) {
        super(message, cause);
        this.category   = category != null ? category : ErrorCategory.UNKNOWN;
        this.provider   = provider;
        this.statusCode = statusCode;
        this.retryable  = retryable;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static EmbeddingException rateLimit(String provider, String message) {
        return new EmbeddingException(message, null, ErrorCategory.RATE_LIMIT, provider, 429, true);
    }

    public static EmbeddingException authenticationError(String provider, String message) {
        return new EmbeddingException(message, null, ErrorCategory.AUTHENTICATION, provider, 401, false);
    }

    public static EmbeddingException invalidRequest(String provider, String message) {
        return new EmbeddingException(message, null, ErrorCategory.INVALID_REQUEST, provider, 400, false);
    }

    public static EmbeddingException serverError(String provider, String message, int statusCode) {
        return new EmbeddingException(message, null, ErrorCategory.SERVER_ERROR, provider, statusCode, true);
    }

    public static EmbeddingException modelNotFound(String provider, String model) {
        return new EmbeddingException(
                String.format("Embedding model '%s' not found for provider '%s'", model, provider),
                null, ErrorCategory.MODEL_NOT_FOUND, provider, 404, false);
    }

    /**
     * A generic network failure — retryable, unlike {@link #connectionError}. Reserved for
     * transports that report a transient network hiccup without the certainty of an
     * unreachable endpoint (mirrors {@code LlmException#networkError}, kept for parity even
     * though adapters are expected to prefer {@link #connectionError} once they can tell the
     * two apart, exactly as the chat adapters do).
     */
    public static EmbeddingException networkError(String provider, String message, Throwable cause) {
        return new EmbeddingException(message, cause, ErrorCategory.NETWORK, provider, null, true);
    }

    /**
     * A connection failure — the transport could not reach the provider endpoint.
     *
     * <p>Non-retryable on the same client: a connection problem (DNS failure, connection
     * refused, broken pipe, connect timeout) indicates the endpoint is unreachable, and
     * retrying immediately would hit the same failure again. But it is still worth
     * {@link #shouldFailover() failing over} to a different embedding provider, which may
     * well be reachable.
     */
    public static EmbeddingException connectionError(String provider, String message, Throwable cause) {
        return new EmbeddingException(message, cause, ErrorCategory.NETWORK, provider, null, false);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ErrorCategory category()   { return category; }
    public String        provider()   { return provider; }
    public Integer       statusCode() { return statusCode; }
    public boolean        isRetryable() { return retryable; }

    /**
     * Whether trying a <em>different</em> {@link EmbeddingClient} (failover) could
     * plausibly succeed after this failure. See {@code LlmException#shouldFailover()} for
     * the full rationale — the two mirror each other via the shared {@link ErrorCategory}.
     *
     * @return {@code true} if the pool should advance to its next candidate
     */
    public boolean shouldFailover() {
        return category.shouldFailover();
    }
}
