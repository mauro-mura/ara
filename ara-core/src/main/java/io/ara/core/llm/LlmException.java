package io.ara.core.llm;

/**
 * Exception thrown during LLM operations.
 *
 * <p>Carries a typed {@link ErrorType} so that callers (e.g. {@code FailoverLlmClient})
 * can decide whether to retry or propagate immediately.
 */
public class LlmException extends RuntimeException {

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
