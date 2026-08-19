package io.ara.core.llm;

/**
 * Exception thrown during LLM operations.
 *
 * <p>Carries a typed {@link ErrorType} so that callers (e.g. {@code FailoverLlmClient})
 * can decide whether to retry or propagate immediately.
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
        PARSE_ERROR,
        SERVER_ERROR,
        CONTEXT_LENGTH_EXCEEDED,
        CONTENT_FILTERED,
        UNSUPPORTED_OPERATION,
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

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ErrorType errorType()  { return errorType; }
    public String    provider()   { return provider; }
    public Integer   statusCode() { return statusCode; }
    public boolean   isRetryable() { return retryable; }

    public boolean isRateLimit()             { return errorType == ErrorType.RATE_LIMIT; }
    public boolean isAuthenticationError()   { return errorType == ErrorType.AUTHENTICATION; }
    public boolean isContextLengthExceeded() { return errorType == ErrorType.CONTEXT_LENGTH_EXCEEDED; }
    public boolean isNetworkError()          { return errorType == ErrorType.NETWORK; }
    public boolean isServerError()           { return errorType == ErrorType.SERVER_ERROR; }
}
