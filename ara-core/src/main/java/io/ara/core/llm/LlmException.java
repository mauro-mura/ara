package io.ara.core.llm;

import io.ara.core.common.ErrorCategory;

/**
 * Exception thrown during LLM operations.
 *
 * <p>Carries a typed {@link ErrorType} so that callers (e.g. {@code FailoverLlmClient})
 * can decide whether to retry or propagate immediately. {@link #shouldFailover()} reduces
 * {@link ErrorType} to the provider-agnostic {@link ErrorCategory} shared with
 * {@code EmbeddingException}, so both exception types and both failover decorators agree
 * on what a network error or a rate limit means.
 */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 8321796692081462011L;
	public enum ErrorType {
        INVALID_REQUEST,
        AUTHENTICATION,
        RATE_LIMIT,
        QUOTA_EXCEEDED,
        MODEL_NOT_FOUND,
        NETWORK,
        TIMEOUT,
        PARSE_ERROR,
        SERVER_ERROR,
        CONTEXT_LENGTH_EXCEEDED,
        CONTENT_FILTERED,
        UNSUPPORTED_OPERATION,
        EMPTY_RESPONSE,
        UNKNOWN
    }

    private final ErrorType errorType;
    private final String    provider;
    private final Integer   statusCode;
    private final boolean   retryable;

    public LlmException(String message) {
        this(message, null, ErrorType.UNKNOWN, null, null, false);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, ErrorType.UNKNOWN, null, null, false);
    }

    public LlmException(String message, Throwable cause, ErrorType errorType,
                        String provider, Integer statusCode, boolean retryable) {
        super(message, cause);
        this.errorType  = errorType != null ? errorType : ErrorType.UNKNOWN;
        this.provider   = provider;
        this.statusCode = statusCode;
        this.retryable  = retryable;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static LlmException rateLimit(String provider, String message) {
        return new LlmException(message, null, ErrorType.RATE_LIMIT, provider, 429, true);
    }

    public static LlmException authenticationError(String provider, String message) {
        return new LlmException(message, null, ErrorType.AUTHENTICATION, provider, 401, false);
    }

    public static LlmException contextLengthExceeded(String provider, String model, int tokens, int maxTokens) {
        String msg = String.format("Context length exceeded: %d tokens (max: %d) for model '%s'",
                tokens, maxTokens, model);
        return new LlmException(msg, null, ErrorType.CONTEXT_LENGTH_EXCEEDED, provider, 400, false);
    }

    public static LlmException networkError(String provider, String message, Throwable cause) {
        return new LlmException(message, cause, ErrorType.NETWORK, provider, null, true);
    }

    /**
     * The endpoint accepted the request but did not answer within the configured timeout.
     *
     * <p>Non-retryable on the same client for the same reason as {@link #connectionError}:
     * whatever made this provider slow (load, a stuck backend) will very likely still be true
     * a moment later, so retrying it burns the same wait again. A different provider is not
     * subject to that same load, so {@link #shouldFailover()} is still {@code true} — see
     * {@link io.ara.core.common.ErrorCategory#TIMEOUT}.
     */
    public static LlmException timeout(String provider, String message, Throwable cause) {
        return new LlmException(message, cause, ErrorType.TIMEOUT, provider, null, false);
    }

    public static LlmException serverError(String provider, String message, int statusCode) {
        return new LlmException(message, null, ErrorType.SERVER_ERROR, provider, statusCode, true);
    }

    public static LlmException invalidRequest(String message) {
        return new LlmException(message, null, ErrorType.INVALID_REQUEST, null, 400, false);
    }

    /**
     * A request the provider rejected as malformed, naming the provider that rejected it.
     *
     * <p>Non-retryable, which is the point: a 400 cannot become a 200 by being sent again, so
     * retrying it burns a strategy iteration and then every fallback in a failover pool for
     * nothing. The provider is carried because a caller reading this needs to know <em>which</em>
     * endpoint refused the payload — the same request is often valid on another one.
     */
    public static LlmException invalidRequest(String provider, String message) {
        return new LlmException(message, null, ErrorType.INVALID_REQUEST, provider, 400, false);
    }

    /**
     * The provider refused to answer because its content filter blocked the request or the
     * response.
     *
     * <p>Distinct from {@link #invalidRequest} even though providers report it with the same
     * status code: the payload is well-formed, so whoever reads the error should not go looking
     * for a bug in it. Non-retryable — the same content will be filtered again.
     */
    public static LlmException contentFiltered(String provider, String message) {
        return new LlmException(message, null, ErrorType.CONTENT_FILTERED, provider, 400, false);
    }

    /**
     * A call carried media of a type the selected client does not support.
     *
     * <p>Non-retryable by design, and that is the whole point: it makes
     * {@code FailoverLlmClient} abort instead of walking down the fallback list, so a
     * text-only fallback can never answer a question about a document it was never sent.
     * The message names the type, the file and the provider, because the caller that
     * attached the file is several layers above wherever this is thrown.
     */
    public static LlmException unsupportedMediaType(String provider, String mimeType,
                                                   String mediaName, java.util.Set<String> supported) {
        String msg = String.format(
                "Provider '%s' does not support media type '%s' (attachment '%s'). Supported: %s",
                provider, mimeType, mediaName, supported.isEmpty() ? "none — text only" : supported);
        return new LlmException(msg, null, ErrorType.UNSUPPORTED_OPERATION, provider, null, false);
    }

    public static LlmException modelNotFound(String provider, String model) {
        return new LlmException(
                String.format("Model '%s' not found for provider '%s'", model, provider),
                null, ErrorType.MODEL_NOT_FOUND, provider, 404, false);
    }

    /**
     * The call completed with no transport/HTTP error, but the provider's answer had no text
     * and no tool call.
     *
     * <p>Retryable — unlike a malformed request, an empty completion is typically
     * non-deterministic sampling noise rather than a structural problem with the request, so
     * the same client can plausibly succeed on a second try. Also worth
     * {@link #shouldFailover() failing over}: a different provider may not reproduce whatever
     * produced the blank answer.
     */
    public static LlmException emptyResponse(String provider, String message) {
        return new LlmException(message, null, ErrorType.EMPTY_RESPONSE, provider, null, true);
    }

    /**
     * A connection failure — the transport could not reach the provider endpoint.
     *
     * <p>Non-retryable on the same client: a connection problem (DNS failure, connection
     * refused, broken pipe, connect timeout) indicates the endpoint is unreachable, and
     * retrying immediately would hit the same failure again. But — unlike an
     * {@link #shouldFailover() auth/invalid-request} failure — it is still worth
     * {@link #shouldFailover() failing over} to a different provider, which may well be
     * reachable: retrying the same host wastes time, switching models may work.
     */
    public static LlmException connectionError(String provider, String message, Throwable cause) {
        return new LlmException(message, cause, ErrorType.NETWORK, provider, null, false);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ErrorType errorType()  { return errorType; }
    public String    provider()   { return provider; }
    public Integer   statusCode() { return statusCode; }
    public boolean   isRetryable() { return retryable; }

    public boolean isRateLimit()             { return errorType == ErrorType.RATE_LIMIT; }
    public boolean isAuthenticationError()   { return errorType == ErrorType.AUTHENTICATION; }
    public boolean isContextLengthExceeded() { return errorType == ErrorType.CONTEXT_LENGTH_EXCEEDED; }
    public boolean isNetworkError()          { return errorType == ErrorType.NETWORK; }
    public boolean isTimeout()               { return errorType == ErrorType.TIMEOUT; }
    public boolean isServerError()           { return errorType == ErrorType.SERVER_ERROR; }
    public boolean isEmptyResponse()         { return errorType == ErrorType.EMPTY_RESPONSE; }

    /**
     * Whether trying a <em>different</em> {@code LlmClient} (failover) could plausibly
     * succeed after this failure.
     *
     * <p>Distinct from {@link #isRetryable()}, which governs retrying the <em>same</em>
     * client: the two are independent decisions. A connection error is not worth retrying
     * locally — the endpoint is unreachable and every attempt hits the same wall — but a
     * different provider may well be reachable, so failover is still worth attempting.
     * An {@code AUTHENTICATION}, {@code INVALID_REQUEST} or {@code UNSUPPORTED_OPERATION}
     * failure, by contrast, would recur on every candidate in the pool and must abort the
     * whole chain.
     *
     * <p>{@code FailoverLlmClient} acts on this, while {@code ReactExecutionSupport} acts on
     * {@link #isRetryable()}.
     *
     * @return {@code true} if the pool should advance to its next candidate
     */
    public boolean shouldFailover() {
        return errorCategory().shouldFailover();
    }

    /**
     * Reduces {@link #errorType} to the provider-agnostic {@link ErrorCategory} shared with
     * {@code EmbeddingException}. {@link #errorType} keeps chat-specific variants
     * ({@code CONTEXT_LENGTH_EXCEEDED}) that have no embedding equivalent; this mapping is
     * what lets {@link #shouldFailover()} and a future cross-cutting caller reason about
     * both exception types identically.
     */
    public ErrorCategory errorCategory() {
        return switch (errorType) {
            case NETWORK                  -> ErrorCategory.NETWORK;
            case TIMEOUT                  -> ErrorCategory.TIMEOUT;
            case SERVER_ERROR             -> ErrorCategory.SERVER_ERROR;
            case RATE_LIMIT               -> ErrorCategory.RATE_LIMIT;
            case QUOTA_EXCEEDED           -> ErrorCategory.QUOTA_EXCEEDED;
            case AUTHENTICATION          -> ErrorCategory.AUTHENTICATION;
            case INVALID_REQUEST,
                 CONTEXT_LENGTH_EXCEEDED -> ErrorCategory.INVALID_REQUEST;
            case CONTENT_FILTERED        -> ErrorCategory.CONTENT_FILTERED;
            case MODEL_NOT_FOUND         -> ErrorCategory.MODEL_NOT_FOUND;
            case UNSUPPORTED_OPERATION   -> ErrorCategory.UNSUPPORTED_OPERATION;
            case PARSE_ERROR             -> ErrorCategory.PARSE_ERROR;
            case EMPTY_RESPONSE          -> ErrorCategory.EMPTY_RESPONSE;
            case UNKNOWN                 -> ErrorCategory.UNKNOWN;
        };
    }
}
