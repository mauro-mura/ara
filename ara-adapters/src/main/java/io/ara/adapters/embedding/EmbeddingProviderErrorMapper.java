package io.ara.adapters.embedding;

import java.io.IOException;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import io.ara.core.memory.EmbeddingException;

/**
 * Classifies a provider failure using langchain4j's typed exception hierarchy, the embedding
 * counterpart of {@code io.ara.adapters.llm.ProviderErrorMapper}.
 *
 * <p>The embedding models ({@code OpenAiEmbeddingModel}, {@code OllamaEmbeddingModel},
 * {@code MistralAiEmbeddingModel}) sit on the same HTTP client stack as the chat models and
 * throw the same {@code RetriableException}/{@code NonRetriableException} hierarchy, so the
 * classification logic is identical — only the produced exception type differs
 * ({@link EmbeddingException} instead of {@code LlmException}). Kept as a separate class
 * rather than a shared generic helper because the two call sites want different return types
 * and there is no third caller to justify the extra indirection.
 *
 * <p>{@link ContentFilteredException} extends {@link InvalidRequestException} but is mapped to
 * the same {@link EmbeddingException#invalidRequest} as its parent, unlike the chat mapper's
 * dedicated {@code contentFiltered} category: embedding providers do not moderate input the way
 * chat completions do, and both categories already agree on {@code isRetryable() == false} and
 * {@code shouldFailover() == false}, so the distinction would carry no behavioural difference
 * here — only a clearer message, which is not worth a matching factory method on
 * {@link EmbeddingException} for a case that stays theoretical for embeddings.
 *
 * @see EmbeddingException
 */
public final class EmbeddingProviderErrorMapper {

    private EmbeddingProviderErrorMapper() {}

    /**
     * How far down the cause chain to look — see
     * {@code io.ara.adapters.llm.ProviderErrorMapper} for why this is a bound rather than
     * cycle detection.
     */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * Maps {@code ex}, or any exception in its cause chain, to an {@link EmbeddingException}
     * with the retryability langchain4j assigned it.
     *
     * @param provider the adapter's provider id, for the message and {@link EmbeddingException#provider()}
     * @param ex       the failure caught by the adapter
     * @return the classified exception, or {@code null} if nothing in the chain is a typed
     *         langchain4j exception — leaving the caller's own fallback in charge
     */
    public static EmbeddingException fromTypedException(String provider, Throwable ex) {
        Throwable c = ex;
        for (int depth = 0; c != null && depth < MAX_CAUSE_DEPTH; c = c.getCause(), depth++) {
            EmbeddingException mapped = classify(provider, c);
            if (mapped != null) return mapped;
        }
        return null;
    }

    /**
     * {@link #fromTypedException} plus the same two-step fallback the chat adapters apply in
     * {@code AbstractLangChain4jLlmClient#fallbackClassify}: a non-retryable connection error
     * for an {@link IOException} anywhere in the chain, a retryable network error otherwise.
     * Always returns a non-null exception — the adapter's last line of defense after its own
     * provider-specific substring checks found nothing.
     *
     * @param provider the adapter's provider id
     * @param msg      the message to carry on the produced exception
     * @param ex       the failure caught by the adapter
     * @return a classified, never-null {@link EmbeddingException}
     */
    public static EmbeddingException fallbackClassify(String provider, String msg, Throwable ex) {
        EmbeddingException typed = fromTypedException(provider, ex);
        if (typed != null) return typed;
        if (ex instanceof IOException || isCausedByIOException(ex)) {
            return EmbeddingException.connectionError(provider, msg, ex);
        }
        return EmbeddingException.networkError(provider, msg, ex);
    }

    private static EmbeddingException classify(String provider, Throwable c) {
        String msg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
        return switch (c) {
            case AuthenticationException e  -> EmbeddingException.authenticationError(provider, msg);
            case ModelNotFoundException e   -> EmbeddingException.modelNotFound(provider, msg);
            case RateLimitException e       -> EmbeddingException.rateLimit(provider, msg);
            case InternalServerException e  -> EmbeddingException.serverError(provider, msg, 500);
            case TimeoutException e         -> EmbeddingException.connectionError(provider, msg, c);
            // Before InvalidRequestException, which it extends — see the class javadoc for
            // why it still collapses to the same category here.
            case ContentFilteredException e -> EmbeddingException.invalidRequest(provider, msg);
            case InvalidRequestException e  -> EmbeddingException.invalidRequest(provider, msg);
            case NonRetriableException e    -> EmbeddingException.invalidRequest(provider, msg);
            case RetriableException e       -> EmbeddingException.connectionError(provider, msg, c);
            default -> null;
        };
    }

    private static boolean isCausedByIOException(Throwable ex) {
        Throwable c = ex.getCause();
        for (int depth = 0; c != null && depth < 8; c = c.getCause(), depth++) {
            if (c instanceof IOException) return true;
        }
        return false;
    }
}
