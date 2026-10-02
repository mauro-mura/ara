package io.ara.adapters.embedding.openai;

import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.ara.adapters.embedding.AbstractEmbeddingClientBuilder;
import io.ara.adapters.embedding.AbstractEmbeddingClientBuilder.Endpoint;
import io.ara.adapters.embedding.AbstractEmbeddingClientBuilder.EmbeddingSettings;
import io.ara.adapters.embedding.EmbeddingEndpointPool;
import io.ara.adapters.embedding.EmbeddingProviderErrorMapper;
import io.ara.adapters.embedding.EmbeddingVectors;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EmbeddingException;

/**
 * {@link EmbeddingClient} adapter for the <a href="https://platform.openai.com/">OpenAI</a>
 * embeddings API, backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Compatible with any OpenAI-compatible embeddings endpoint via
 * {@link Builder#endpoint(String, String)}, the same way {@code OpenAiLlmClient} works for chat.
 *
 * <pre>{@code
 * EmbeddingClient embeddings = OpenAiEmbeddingClient.builder()
 *     .modelName("text-embedding-3-small")
 *     .dimensions(1536)
 *     .endpoint(System.getenv("OPENAI_API_KEY"))                          // primary
 *     .endpoint("https://my-gw.internal/v1", System.getenv("GW_KEY"))     // failover
 *     .build();
 * }</pre>
 *
 * <p>More than one {@link Builder#endpoint} call makes {@link #embed} fail over across them in
 * declaration order via {@code EmbeddingEndpointPool} — see its javadoc for why that pool only
 * ever holds endpoints of <em>this one</em> model, never a mix of models.
 *
 * <h2>Retries</h2>
 * <p>{@code maxRetries(0)} on the wrapped model, one per endpoint: this adapter's own
 * resilience owns retry/failover decisions, so LangChain4j's built-in retry is disabled for the
 * same reason every chat adapter disables it — a silent retry inside the model would hide the
 * failure this adapter needs to see and classify.
 *
 * @see EmbeddingClient
 */
public final class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final String PROVIDER = "openai";

    private final EmbeddingClient delegate;
    private final String          modelName;
    private final int             dimensions;
    /**
     * Forces HTTP/1.1 on the embedding model's underlying JDK {@code HttpClient} — the exact
     * counterpart of {@code OpenAiLlmClient}'s flag, for the exact same class of endpoint.
     *
     * <p>A vLLM served through uvicorn's {@code httptools} path drops the body of any request
     * that carries {@code Connection: Upgrade} and logs {@code "Unsupported upgrade request."}:
     * the parser mistakes the HTTP/2 h2c upgrade handshake the JDK client may attempt for a
     * WebSocket handshake, refuses it, and hands the application a body-less request — so the
     * embedding call fails before the model is ever reached. Pinning HTTP/1.1 removes the
     * upgrade header, which is what makes the request go through unchanged. Same fix, same
     * reasoning as the chat client; see {@code OpenAiLlmClient}'s {@code forceHttp1}.
     */
    private final boolean         forceHttp1;

    private OpenAiEmbeddingClient(Builder builder) {
        EmbeddingSettings s = builder.settings();
        this.modelName  = s.modelName();
        this.dimensions = s.dimensions();
        this.forceHttp1 = builder.forceHttp1;
        List<EmbeddingClient> perEndpoint = s.endpoints().stream()
                .map(endpoint -> buildEndpointClient(endpoint, s))
                .toList();
        this.delegate = perEndpoint.size() == 1 ? perEndpoint.get(0) : new EmbeddingEndpointPool(perEndpoint);
    }

    private EmbeddingClient buildEndpointClient(Endpoint endpoint, EmbeddingSettings s) {
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder modelBuilder = OpenAiEmbeddingModel.builder()
                .apiKey(endpoint.apiKey())
                .baseUrl(endpoint.baseUrl())
                .modelName(s.modelName())
                .dimensions(s.dimensions())
                .timeout(s.timeout())
                .logRequests(s.logRequests())
                .logResponses(s.logResponses())
                .maxRetries(0);

        if (forceHttp1) {
            // Force HTTP/1.1 so no h2c upgrade header is ever sent — see the forceHttp1 field
            // javadoc. Mirrors OpenAiLlmClient's streaming-model workaround exactly.
            java.net.http.HttpClient.Builder jdkBuilder = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1);
            JdkHttpClientBuilder jdkHttpClientBuilder =
                    JdkHttpClient.builder().httpClientBuilder(jdkBuilder);
            modelBuilder.httpClientBuilder(jdkHttpClientBuilder);
        }

        return new EndpointClient(modelBuilder.build(), endpoint.baseUrl());
    }

    @Override
    public List<Float> embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public List<List<Float>> embedAll(List<String> texts) {
        return delegate.embedAll(texts);
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
     * One reachable OpenAI embeddings endpoint — the unit {@code EmbeddingEndpointPool} fails
     * over across. A non-static inner class so it can share {@link #mapException} and the
     * outer {@link #modelName}/{@link #dimensions} without duplicating either.
     */
    private final class EndpointClient implements EmbeddingClient {
        private final OpenAiEmbeddingModel model;
        private final String               providerId;

        EndpointClient(OpenAiEmbeddingModel model, String baseUrl) {
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

        @Override
        public List<List<Float>> embedAll(List<String> texts) {
            try {
                List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
                List<Embedding> embeddings = model.embedAll(segments).content();
                List<List<Float>> vectors = new java.util.ArrayList<>(embeddings.size());
                for (Embedding embedding : embeddings) {
                    vectors.add(EmbeddingVectors.validate(
                            PROVIDER, modelName, embedding.vectorAsList(), dimensions));
                }
                return vectors;
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
        if (msg.contains("401") || msg.contains("invalid_api_key")) {
            return EmbeddingException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate_limit_exceeded")) {
            return EmbeddingException.rateLimit(PROVIDER, msg);
        }
        return EmbeddingProviderErrorMapper.fallbackClassify(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link OpenAiEmbeddingClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link OpenAiEmbeddingClient}.
     *
     * <p>Shared configuration lives in {@link AbstractEmbeddingClientBuilder}. Required:
     * {@code modelName}, {@code dimensions}, at least one {@link #endpoint}.
     */
    public static final class Builder extends AbstractEmbeddingClientBuilder<Builder> {

        private boolean forceHttp1 = false;

        /**
         * Forces HTTP/1.1 on the embedding model, preventing an HTTP/2 h2c upgrade handshake
         * that some OpenAI-compatible servers reject. Set this to {@code true} when the
         * endpoint is a vLLM (or similar) that logs {@code "Unsupported upgrade request."} and
         * returns an empty/failed embedding — the uvicorn {@code httptools} parser drops the
         * body of any request carrying {@code Connection: Upgrade}. Same flag, same reason as
         * {@code OpenAiLlmClient.Builder#forceHttp1(boolean)}.
         */
        public Builder forceHttp1(boolean v) { this.forceHttp1 = v; return this; }

        /**
         * Builds the {@link OpenAiEmbeddingClient}.
         *
         * @throws IllegalStateException    if no {@link #endpoint} was ever declared
         * @throws IllegalArgumentException if {@code modelName} is blank or {@code dimensions}
         *                                   was never set or is not positive
         */
        public OpenAiEmbeddingClient build() {
            if (endpoints.isEmpty()) throw new IllegalStateException("At least one OpenAI endpoint is required");
            if (modelName == null || modelName.isBlank())
                throw new IllegalArgumentException("Model name is required");
            if (dimensions == null || dimensions < 1)
                throw new IllegalArgumentException("dimensions must be set and >= 1");
            return new OpenAiEmbeddingClient(this);
        }
    }
}
