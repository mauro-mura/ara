package io.ara.adapters.embedding.mistral;

import java.util.List;

import dev.langchain4j.model.mistralai.MistralAiEmbeddingModel;
import io.ara.adapters.embedding.AbstractEmbeddingClientBuilder;
import io.ara.adapters.embedding.AbstractEmbeddingClientBuilder.Endpoint;
import io.ara.adapters.embedding.AbstractEmbeddingClientBuilder.EmbeddingSettings;
import io.ara.adapters.embedding.EmbeddingEndpointPool;
import io.ara.adapters.embedding.EmbeddingProviderErrorMapper;
import io.ara.adapters.embedding.EmbeddingVectors;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EmbeddingException;

/**
 * {@link EmbeddingClient} adapter for the <a href="https://docs.mistral.ai/">Mistral AI</a>
 * embeddings API, backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <pre>{@code
 * EmbeddingClient embeddings = MistralAiEmbeddingClient.builder()
 *     .dimensions(1024)
 *     .endpoint(System.getenv("MISTRAL_API_KEY"))
 *     .build();
 * }</pre>
 *
 * <p>More than one {@link Builder#endpoint} call makes {@link #embed} fail over across them —
 * see {@code EmbeddingEndpointPool}.
 *
 * <h2>{@code dimensions()} is not configurable on the wire</h2>
 * <p>Unlike OpenAI and Ollama, Mistral's embeddings API takes no dimension parameter —
 * {@code mistral-embed} always returns a 1024-float vector, full stop. {@link Builder#dimensions(int)}
 * is still required here, exactly as for the other two adapters: it is what {@link #dimensions()}
 * reports to the vector store, and every {@link #embed} call validates the actual response
 * against it, so a caller who configures the wrong value gets a clear
 * {@link EmbeddingException} naming the mismatch instead of a vector store silently corrupted
 * by wrongly-sized vectors.
 *
 * @see EmbeddingClient
 */
public final class MistralAiEmbeddingClient implements EmbeddingClient {

    private static final String PROVIDER = "mistral";

    /** The only embedding model Mistral currently offers, at 1024 dimensions. */
    public static final String DEFAULT_MODEL = "mistral-embed";

    private final EmbeddingClient delegate;
    private final String          modelName;
    private final int             dimensions;

    private MistralAiEmbeddingClient(Builder builder) {
        EmbeddingSettings s = builder.settings();
        this.modelName  = s.modelName();
        this.dimensions = s.dimensions();
        List<EmbeddingClient> perEndpoint = s.endpoints().stream()
                .map(endpoint -> buildEndpointClient(endpoint, s))
                .toList();
        this.delegate = perEndpoint.size() == 1 ? perEndpoint.get(0) : new EmbeddingEndpointPool(perEndpoint);
    }

    private EmbeddingClient buildEndpointClient(Endpoint endpoint, EmbeddingSettings s) {
        MistralAiEmbeddingModel model = MistralAiEmbeddingModel.builder()
                .apiKey(endpoint.apiKey())
                .baseUrl(endpoint.baseUrl())
                .modelName(s.modelName())
                .timeout(s.timeout())
                .logRequests(s.logRequests())
                .logResponses(s.logResponses())
                .maxRetries(0)
                .build();
        return new EndpointClient(model, endpoint.baseUrl());
    }

    @Override
    public List<Float> embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelName;
    }

    /**
     * One reachable Mistral embeddings endpoint — the unit {@code EmbeddingEndpointPool} fails
     * over across. A non-static inner class so it can share {@link #mapException} and the
     * outer {@link #modelName}/{@link #dimensions} without duplicating either.
     */
    private final class EndpointClient implements EmbeddingClient {
        private final MistralAiEmbeddingModel model;
        private final String                  providerId;

        EndpointClient(MistralAiEmbeddingModel model, String baseUrl) {
            this.model      = model;
            this.providerId = PROVIDER + "-" + modelName
                    + (baseUrl != null && !baseUrl.isBlank() ? "@" + baseUrl : "");
        }

        @Override
        public List<Float> embed(String text) {
            try {
                List<Float> vector = model.embed(text).content().vectorAsList();
                return EmbeddingVectors.validate(PROVIDER, modelName, vector, dimensions);
            } catch (EmbeddingException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw mapException(ex);
            }
        }

        @Override public int dimensions() { return dimensions; }
        @Override public String providerId() { return providerId; }
    }

    private EmbeddingException mapException(Throwable ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("api_key")) {
            return EmbeddingException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("rate_limit")) {
            return EmbeddingException.rateLimit(PROVIDER, msg);
        }
        return EmbeddingProviderErrorMapper.fallbackClassify(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link MistralAiEmbeddingClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link MistralAiEmbeddingClient}. Defaults {@code modelName} to
     * {@link #DEFAULT_MODEL}. Required: {@code dimensions}, at least one {@link #endpoint}.
     */
    public static final class Builder extends AbstractEmbeddingClientBuilder<Builder> {

        public Builder() {
            modelName = DEFAULT_MODEL;
        }

        /**
         * Builds the {@link MistralAiEmbeddingClient}.
         *
         * @throws IllegalStateException    if no {@link #endpoint} was ever declared
         * @throws IllegalArgumentException if {@code dimensions} was never set or is not positive
         */
        public MistralAiEmbeddingClient build() {
            if (endpoints.isEmpty()) throw new IllegalStateException("At least one Mistral endpoint is required");
            if (dimensions == null || dimensions < 1)
                throw new IllegalArgumentException("dimensions must be set and >= 1");
            return new MistralAiEmbeddingClient(this);
        }
    }
}
