package io.ara.core.common;

/**
 * Provider-agnostic classification of a remote-call failure, shared between
 * {@code LlmException} (chat) and {@code EmbeddingException} (embeddings).
 *
 * <p>Both exception types wrap a richer, domain-specific error type (chat has
 * {@code CONTEXT_LENGTH_EXCEEDED}, embeddings do not need it) but ultimately reduce to the
 * same two decisions any caller needs: is this worth retrying against the very endpoint
 * that just failed ({@link #isRetryable()}), and is it worth trying a <em>different</em>
 * endpoint instead ({@link #shouldFailover()})? Centralising that reduction here means both
 * exception types — and both failover decorators, {@code io.ara.runtime.factory.FailoverLlmClient}
 * and {@code io.ara.adapters.embedding.EmbeddingEndpointPool} — agree on what a network error or
 * a rate limit means, without one copying the other's switch statement.
 */
public enum ErrorCategory {
    /** Transport could not reach the endpoint (DNS, connection refused, broken pipe, connect timeout). */
    NETWORK,
    /**
     * The endpoint was reached and accepted the request, but did not answer within the
     * configured timeout. Distinct from {@link #NETWORK}: the connection itself was fine, so
     * this points at the provider being slow/overloaded, not at connectivity — a different
     * signal for whoever is reading the logs, even though the caller's response (retry the
     * same endpoint? no; try another? yes) is identical to {@link #NETWORK}.
     */
    TIMEOUT,
    /** The provider's server itself failed (5xx). */
    SERVER_ERROR,
    /** The caller exceeded its request-rate allowance (429). */
    RATE_LIMIT,
    /** The caller's account/plan quota is exhausted (a 429/402 that will not clear itself). */
    QUOTA_EXCEEDED,
    /** Credentials rejected (401/403). */
    AUTHENTICATION,
    /** The request itself was malformed (400 and not otherwise classified). */
    INVALID_REQUEST,
    /** The provider refused to process well-formed content (moderation/safety filter). */
    CONTENT_FILTERED,
    /** The named model does not exist for this provider. */
    MODEL_NOT_FOUND,
    /** The call used a capability this client does not support. */
    UNSUPPORTED_OPERATION,
    /** The provider's response could not be parsed. */
    PARSE_ERROR,
    /**
     * The call completed with no transport/HTTP error, but the provider's answer was blank —
     * no text and no tool call. Worth a retry (often non-deterministic) and worth failing over
     * (another provider may not reproduce it), unlike a genuinely malformed request.
     */
    EMPTY_RESPONSE,
    /** Uncategorised. */
    UNKNOWN;

    /**
     * Whether the very same call is worth repeating against the same endpoint.
     *
     * <p>Only transient server-side states qualify: a rate limit clears with time, a 5xx may
     * be a momentary blip. Everything else — a malformed request, bad credentials, an
     * unreachable host — will fail again exactly the same way, so retrying it locally only
     * burns a strategy iteration.
     */
    public boolean isRetryable() {
        return switch (this) {
            case RATE_LIMIT, SERVER_ERROR, EMPTY_RESPONSE -> true;
            default -> false;
        };
    }

    /**
     * Whether trying a <em>different</em> endpoint could plausibly succeed after this
     * failure.
     *
     * <p>Distinct from {@link #isRetryable()}: a connection error is not worth retrying
     * against the same host — it is unreachable, every attempt hits the same wall — but a
     * different provider may well be reachable, so failover is still worth attempting. An
     * {@code AUTHENTICATION}, {@code INVALID_REQUEST} or {@code UNSUPPORTED_OPERATION}
     * failure, by contrast, would recur on every candidate in a pool and must abort the
     * whole chain instead.
     */
    public boolean shouldFailover() {
        return switch (this) {
            case NETWORK, TIMEOUT, SERVER_ERROR, RATE_LIMIT, QUOTA_EXCEEDED, EMPTY_RESPONSE -> true;
            default -> false;
        };
    }
}
