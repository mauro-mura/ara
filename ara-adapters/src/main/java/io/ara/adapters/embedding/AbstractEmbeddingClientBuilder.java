package io.ara.adapters.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared fluent-builder surface for the LangChain4j-backed, keyed embedding adapters.
 *
 * <p>{@code OpenAiEmbeddingClient} and {@code MistralAiEmbeddingClient} configure the same
 * knobs (endpoints, model, dimensions, timeout, request/response logging) — the embedding
 * counterpart of {@code io.ara.adapters.llm.AbstractLlmClientBuilder}, minus the sampling knobs
 * (temperature, max tokens) that have no meaning for an embedding call.
 *
 * <p><b>{@code OllamaEmbeddingClient} does not extend this.</b> Ollama's embedding endpoints
 * take no API key — {@code OllamaEmbeddingModel.builder()} exposes no such setter — so
 * inheriting {@link Endpoint}, which always pairs a base URL with a key, would let a caller
 * set one on an Ollama client and have it silently do nothing: the same failure mode
 * {@code AbstractLlmClientBuilder}'s javadoc documents for why the chat adapter's Ollama
 * builder stands alone too.
 *
 * <h2>{@code endpoint(...)} instead of {@code apiKey(...)}/{@code baseUrl(...)}</h2>
 * <p>An earlier shape of this builder had single {@code apiKey}/{@code baseUrl} fields, and
 * resilience was added on top of it by pooling arbitrary, separately-built
 * {@code EmbeddingClient} instances at the runtime layer. That let a pool mix two different
 * <em>models</em> — which is unsafe in a way a chat-model pool never is: two embedding vectors
 * from different models are not comparable even at equal {@link #dimensions(int)}, because they
 * live in different latent spaces, so a pool that silently switched models would corrupt
 * whatever vector store it fed. Declaring model and dimensions exactly once here, with
 * {@link #endpoint} contributing only <em>where</em> to reach that one model, makes mixing
 * models a compile-time impossibility instead of a caller discipline this builder had to trust.
 * See {@code EmbeddingEndpointPool} for the failover this list feeds.
 *
 * @param <B> the concrete builder type, so every inherited setter returns the adapter's own
 *            {@code Builder} and chaining keeps working
 * @see AbstractEmbeddingClientBuilder.EmbeddingSettings
 */
public abstract class AbstractEmbeddingClientBuilder<B extends AbstractEmbeddingClientBuilder<B>> {

    /**
     * One reachable copy of this builder's model: a base URL paired with the API key that
     * authenticates against it. {@code baseUrl} is {@code null} for the provider's own default
     * endpoint (e.g. hosted OpenAI) — only an override (Azure, a gateway, a proxy) sets it.
     */
    public record Endpoint(String baseUrl, String apiKey) {
        public Endpoint {
            Objects.requireNonNull(apiKey, "apiKey must not be null");
        }
    }

    /** Ordered list of reachable copies of the model. First = primary, rest = failover. */
    protected final List<Endpoint> endpoints = new ArrayList<>();
    /** Model identifier. Null until set, or given a default by a subclass constructor. */
    protected String modelName;
    /** Vector dimension this client is configured to produce. Null until set. */
    protected Integer dimensions;
    /** HTTP request timeout. Defaults to {@code 60s}. */
    protected Duration timeout = Duration.ofSeconds(60);
    /** LangChain4j request logging to SLF4J. Off by default. */
    protected boolean logRequests;
    /** LangChain4j response logging to SLF4J. Off by default. */
    protected boolean logResponses;

    /**
     * Snapshot of the shared settings, consumed by the embedding client constructors — see
     * {@code AbstractLlmClientBuilder.LlmSettings} for why a snapshot rather than getters.
     */
    public record EmbeddingSettings(
            List<Endpoint> endpoints,
            String modelName,
            Integer dimensions,
            Duration timeout,
            boolean logRequests,
            boolean logResponses) {}

    /**
     * Returns the current shared settings as a snapshot, defaults applied.
     *
     * @return the shared fields, with defaults applied
     */
    public final EmbeddingSettings settings() {
        return new EmbeddingSettings(List.copyOf(endpoints), modelName, dimensions,
                timeout, logRequests, logResponses);
    }

    /** The concrete {@code Builder} for {@code this}, so inherited setters stay fluent. */
    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

    /**
     * Adds a reachable copy of this builder's model at the provider's own default URL,
     * authenticated with {@code apiKey}. The first {@code endpoint(...)} call (of either
     * overload) is the primary; every later one is a failover candidate tried in declaration
     * order when the primary's failure is one {@code EmbeddingException.shouldFailover()}
     * reports as worth retrying elsewhere (network, 5xx, rate limit) — see
     * {@code EmbeddingEndpointPool}. At least one endpoint is required.
     */
    public B endpoint(String apiKey) { this.endpoints.add(new Endpoint(null, apiKey)); return self(); }

    /**
     * Adds a reachable copy of this builder's model at {@code baseUrl} (a proxy, a gateway, an
     * Azure OpenAI deployment, …), authenticated with {@code apiKey}. See {@link #endpoint(String)}
     * for ordering.
     */
    public B endpoint(String baseUrl, String apiKey) {
        this.endpoints.add(new Endpoint(baseUrl, apiKey));
        return self();
    }

    /** Sets the model by string ID. */
    public B modelName(String modelName) { this.modelName = modelName; return self(); }

    /**
     * The vector dimension this client is configured to produce — must match the vector store
     * collection it feeds. Required.
     */
    public B dimensions(int dimensions)  { this.dimensions = dimensions; return self(); }

    /** HTTP request timeout. Defaults to {@code 60s}. */
    public B timeout(Duration timeout)   { this.timeout = timeout; return self(); }

    /** Enables LangChain4j request logging to SLF4J. */
    public B logRequests(boolean v)      { this.logRequests = v;   return self(); }

    /** Enables LangChain4j response logging to SLF4J. */
    public B logResponses(boolean v)     { this.logResponses = v;  return self(); }
}
