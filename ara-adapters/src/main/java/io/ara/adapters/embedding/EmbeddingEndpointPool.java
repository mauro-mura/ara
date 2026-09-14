package io.ara.adapters.embedding;

import java.util.List;
import java.util.Objects;

import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EmbeddingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ordered failover across multiple endpoints of the <em>same</em> embedding model.
 *
 * <p>On each call to {@link #embed}, it tries endpoints in declaration order.
 * {@link EmbeddingException}s with {@link EmbeddingException#shouldFailover()} {@code false}
 * (e.g. authentication errors, invalid requests) are re-thrown immediately without attempting
 * further fallbacks — the same failure would recur on every candidate. Failures where another
 * endpoint could plausibly succeed ({@code shouldFailover()} {@code true}: rate-limits, server
 * errors, network and connection problems) and generic {@link RuntimeException}s advance to the
 * next endpoint. Only when every endpoint is exhausted is the last exception re-thrown. Same
 * shape as {@code io.ara.runtime.factory.FailoverLlmClient.complete}, simpler since
 * {@link EmbeddingClient} has no streaming call to replicate the before-first-token constraint
 * for.
 *
 * <h2>Why "endpoints of the same model", not "a pool of embedding clients"</h2>
 * <p>A pool that could hold clients for <em>different</em> models would let a caller mix them —
 * and unlike chat completions, two embedding vectors from different models are not comparable
 * even at equal {@link #dimensions()}: they live in different latent spaces, so switching
 * models mid-pool would silently poison whatever vector store this feeds. The supported way to
 * reach this constructor is exclusively the three adapter builders (see
 * {@code AbstractEmbeddingClientBuilder#endpoint}), each of which already fixes one model name
 * and one dimension before building any endpoint — so "different models in one pool" is not
 * merely discouraged on that path, it has no code path that can express it.
 *
 * <h2>Why this lives in ara-adapters, not ara-runtime</h2>
 * <p>The rejected shape (kept here as the reason not to redo it) was a runtime-level decorator
 * over an arbitrary {@code List<EmbeddingClient>}, built once in {@code AraRuntime.Builder} from
 * whatever clients a caller happened to pass in. That is exactly what let the "different models"
 * problem above be expressed at all: nothing in that list's type stopped it from holding an
 * OpenAI and a Mistral client side by side. Building the list from a single builder's own
 * endpoints instead — one model, N reachable copies — needs the model identity to be in scope,
 * which only the adapter constructing it has; it cannot be reconstructed later from a bare
 * {@code List<EmbeddingClient>} handed to a general-purpose runtime pool.
 *
 * <p>This class is package-visible only in the sense that its constructor is public out of
 * necessity — the three adapters live in {@code embedding.openai}, {@code embedding.ollama} and
 * {@code embedding.mistral} sub-packages, so a helper they all call must be public in this
 * parent package (the same constraint {@code EmbeddingVectors} and
 * {@code EmbeddingProviderErrorMapper} are already public for). Honestly: nothing stops a
 * caller who imports this class directly from constructing a pool across two different models
 * by hand, the same way the discarded runtime-level pool allowed. The dimension check in the
 * constructor catches the mechanical case (mismatched {@link #dimensions()}), not the deeper
 * semantic one (same dimension, different latent space) — the actual guarantee comes from there
 * being no supported path to this constructor other than a single builder's own endpoint list.
 */
public final class EmbeddingEndpointPool implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingEndpointPool.class);

    private final List<EmbeddingClient> endpoints;
    private final String                providerId;
    private final int                   dimensions;
    private volatile String             lastUsedEndpoint;

    public EmbeddingEndpointPool(List<EmbeddingClient> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        if (endpoints.isEmpty()) throw new IllegalArgumentException("At least one endpoint required");
        this.endpoints  = List.copyOf(endpoints);
        this.dimensions = this.endpoints.get(0).dimensions();
        for (EmbeddingClient e : this.endpoints) {
            if (e.dimensions() != dimensions) {
                throw new IllegalArgumentException(
                        "All endpoints in a pool must share the same dimensions: '"
                                + e.providerId() + "' reports " + e.dimensions()
                                + ", but '" + this.endpoints.get(0).providerId() + "' reports " + dimensions);
            }
        }
        this.providerId       = buildCompositeId(this.endpoints);
        this.lastUsedEndpoint = this.endpoints.get(0).providerId();
    }

    @Override
    public List<Float> embed(String text) {
        EmbeddingException lastEmbeddingFailure = null;
        RuntimeException   lastFailure           = null;

        for (int i = 0; i < endpoints.size(); i++) {
            EmbeddingClient candidate = endpoints.get(i);
            try {
                List<Float> result = candidate.embed(text);
                if (i > 0) {
                    log.info("Failover succeeded with endpoint '{}' (primary failed after {} attempt(s))",
                            candidate.providerId(), i);
                }
                lastUsedEndpoint = candidate.providerId();
                return result;
            } catch (EmbeddingException ex) {
                if (!ex.shouldFailover()) {
                    log.error("Embedding endpoint '{}' returned non-failover error [{}] — aborting failover: {}",
                            candidate.providerId(), ex.category(), ex.getMessage());
                    throw ex;
                }
                lastEmbeddingFailure = ex;
                logAttempt(candidate, i, ex.category().name(), ex.getMessage());
            } catch (RuntimeException ex) {
                lastFailure = ex;
                logAttempt(candidate, i, null, ex.getMessage());
            }
        }

        if (lastEmbeddingFailure != null) throw lastEmbeddingFailure;
        throw lastFailure;
    }

    private void logAttempt(EmbeddingClient candidate, int i, String category, String message) {
        boolean hasNext = i < endpoints.size() - 1;
        String  tag     = category != null ? " [" + category + "]" : "";
        if (hasNext) {
            log.warn("Embedding endpoint '{}' failed{} — switching to next fallback. Reason: {}",
                    candidate.providerId(), tag, message);
        } else {
            log.error("All {} embedding endpoint(s) failed. Last error from '{}'{}: {}",
                    endpoints.size(), candidate.providerId(), tag, message);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    /** Which endpoint actually served the most recent {@link #embed} call. */
    public String lastUsedEndpoint() {
        return lastUsedEndpoint;
    }

    private static String buildCompositeId(List<EmbeddingClient> endpoints) {
        return "failover[" + endpoints.stream()
                .map(EmbeddingClient::providerId)
                .reduce((a, b) -> a + "→" + b)
                .orElse("empty") + "]";
    }
}
