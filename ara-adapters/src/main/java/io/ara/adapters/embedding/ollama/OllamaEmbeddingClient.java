package io.ara.adapters.embedding.ollama;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import io.ara.adapters.embedding.EmbeddingEndpointPool;
import io.ara.adapters.embedding.EmbeddingProviderErrorMapper;
import io.ara.adapters.embedding.EmbeddingVectors;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EmbeddingException;

/**
 * {@link EmbeddingClient} adapter for <a href="https://ollama.com/">Ollama</a>, backed by
 * <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>No API key needed — only a running Ollama instance and an embedding model pulled via
 * {@code ollama pull <model>} (e.g. {@code nomic-embed-text}, {@code mxbai-embed-large}).
 *
 * <pre>{@code
 * EmbeddingClient embeddings = OllamaEmbeddingClient.builder()
 *     .modelName("nomic-embed-text")
 *     .dimensions(768)
 *     .endpoint("http://localhost:11434")            // primary
 *     .endpoint("http://ollama-replica:11434")        // failover
 *     .build();
 * }</pre>
 *
 * <p>More than one {@link Builder#endpoint} call makes {@link #embed} fail over across them —
 * see {@code EmbeddingEndpointPool}. With none declared, the single default
 * {@code http://localhost:11434} is used.
 *
 * <h2>Standalone builder</h2>
 * <p>Does not extend {@code AbstractEmbeddingClientBuilder}: Ollama's endpoints take no API key
 * — {@code AbstractEmbeddingClientBuilder.Endpoint} always pairs a base URL with one, so
 * inheriting it would let a caller set a key on an Ollama endpoint and have it silently do
 * nothing — the same reasoning {@code OllamaLlmClient} documents for standing alone among the
 * chat adapters.
 *
 * @see EmbeddingClient
 */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    private static final String PROVIDER          = "ollama";
    private static final String DEFAULT_BASE_URL  = "http://localhost:11434";

    private final EmbeddingClient delegate;
    private final String          modelName;
    private final int             dimensions;

    private OllamaEmbeddingClient(Builder builder) {
        this.modelName  = builder.modelName;
        this.dimensions = builder.dimensions;
        List<String> resolvedUrls = builder.endpoints.isEmpty()
                ? List.of(DEFAULT_BASE_URL)
                : List.copyOf(builder.endpoints);
        List<EmbeddingClient> perEndpoint = resolvedUrls.stream()
                .map(url -> buildEndpointClient(url, builder))
                .toList();
        this.delegate = perEndpoint.size() == 1 ? perEndpoint.get(0) : new EmbeddingEndpointPool(perEndpoint);
    }

    private EmbeddingClient buildEndpointClient(String baseUrl, Builder builder) {
        OllamaEmbeddingModel model = OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(builder.modelName)
                .dimensions(builder.dimensions)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .maxRetries(0)
                .build();
        return new EndpointClient(model, baseUrl);
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
     * One reachable Ollama embeddings endpoint — the unit {@code EmbeddingEndpointPool} fails
     * over across. A non-static inner class so it can share {@link #mapException} and the
     * outer {@link #modelName}/{@link #dimensions} without duplicating either.
     */
    private final class EndpointClient implements EmbeddingClient {
        private final OllamaEmbeddingModel model;
        private final String               providerId;

        EndpointClient(OllamaEmbeddingModel model, String baseUrl) {
            this.model      = model;
            this.providerId = PROVIDER + "-" + modelName + "@" + baseUrl;
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
        if (msg.contains("Connection refused") || msg.contains("connect")) {
            return EmbeddingException.connectionError(PROVIDER,
                    "Cannot reach Ollama at the configured base URL. Is Ollama running?", ex);
        }
        if (msg.contains("404") || msg.contains("model not found") || msg.contains("pull model")) {
            return EmbeddingException.modelNotFound(PROVIDER, modelName);
        }
        return EmbeddingProviderErrorMapper.fallbackClassify(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link OllamaEmbeddingClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link OllamaEmbeddingClient}.
     *
     * <p>Defaults: {@code http://localhost:11434} when no {@link #endpoint} is declared,
     * 2-minute timeout. Required: {@code modelName}, {@code dimensions}.
     */
    public static final class Builder {
        private final List<String> endpoints    = new ArrayList<>();
        private String   modelName;
        private Integer  dimensions;
        private Duration timeout      = Duration.ofMinutes(2);
        private boolean  logRequests  = false;
        private boolean  logResponses = false;

        /**
         * Adds a reachable copy of this builder's model at {@code baseUrl}. The first call is
         * the primary; every later one is a failover candidate tried in declaration order —
         * see {@code EmbeddingEndpointPool}. Defaults to a single
         * {@code http://localhost:11434} endpoint when never called.
         */
        public Builder endpoint(String baseUrl) { this.endpoints.add(baseUrl); return this; }

        /** Sets the model by string ID (e.g. {@code "nomic-embed-text"}). */
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }

        /**
         * The vector dimension this client is configured to produce — must match the vector
         * store collection it feeds. Required.
         */
        public Builder dimensions(int dimensions)  { this.dimensions = dimensions; return this; }

        /** HTTP request timeout. Defaults to {@code 2 minutes}. */
        public Builder timeout(Duration timeout)   { this.timeout = timeout; return this; }

        /** Enables LangChain4j request logging to SLF4J. */
        public Builder logRequests(boolean v)      { this.logRequests = v; return this; }

        /** Enables LangChain4j response logging to SLF4J. */
        public Builder logResponses(boolean v)     { this.logResponses = v; return this; }

        /**
         * Builds the {@link OllamaEmbeddingClient}.
         *
         * @throws IllegalArgumentException if a declared endpoint is blank, {@code modelName}
         *                                   is blank, or {@code dimensions} was never set or is
         *                                   not positive
         */
        public OllamaEmbeddingClient build() {
            for (String url : endpoints) {
                if (url == null || url.isBlank())
                    throw new IllegalArgumentException("An Ollama endpoint base URL must not be blank");
            }
            if (modelName == null || modelName.isBlank())
                throw new IllegalArgumentException("Model name is required");
            if (dimensions == null || dimensions < 1)
                throw new IllegalArgumentException("dimensions must be set and >= 1");
            return new OllamaEmbeddingClient(this);
        }
    }
}
