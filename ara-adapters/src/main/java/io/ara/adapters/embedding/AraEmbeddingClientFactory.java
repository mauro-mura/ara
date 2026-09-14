package io.ara.adapters.embedding;

import io.ara.adapters.embedding.mistral.MistralAiEmbeddingClient;
import io.ara.adapters.embedding.ollama.OllamaEmbeddingClient;
import io.ara.adapters.embedding.openai.OpenAiEmbeddingClient;
import io.ara.core.memory.EmbeddingClient;

/**
 * Factory that provides builder access for all ARA-supported embedding adapters — the
 * embedding counterpart of {@code io.ara.adapters.llm.AraLlmClientFactory}.
 *
 * <p>Each method returns the adapter's fluent builder so a client can be configured and built
 * in a single expression, then wired wherever an {@link EmbeddingClient} is expected (a
 * {@code DocumentStore}/{@code InMemoryDocumentStore} constructor, or
 * {@code AraRuntime.Builder#embeddingClient}):
 *
 * <pre>{@code
 * EmbeddingClient openai = AraEmbeddingClientFactory.openAi()
 *     .modelName("text-embedding-3-small")
 *     .dimensions(1536)
 *     .endpoint(System.getenv("OPENAI_API_KEY"))                       // primary
 *     .endpoint("https://my-gw.internal/v1", System.getenv("GW_KEY"))  // failover, same model
 *     .build();
 *
 * EmbeddingClient local = AraEmbeddingClientFactory.ollama()
 *     .modelName("nomic-embed-text")
 *     .dimensions(768)
 *     .endpoint("http://localhost:11434")
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient(chatClient)
 *     .embeddingClient(openai)
 *     .semanticStore(store)
 *     .build();
 * }</pre>
 *
 * <p>Resilience across endpoints lives inside one client, not across several: every
 * {@code endpoint(...)} call on the same builder must reach the same model, because two
 * embedding vectors from different models are not comparable even at equal
 * {@code dimensions()} — see {@code EmbeddingEndpointPool}.
 *
 * @see OpenAiEmbeddingClient
 * @see OllamaEmbeddingClient
 * @see MistralAiEmbeddingClient
 * @see EmbeddingClient
 */
public final class AraEmbeddingClientFactory {

    private AraEmbeddingClientFactory() {}

    /**
     * Returns a builder for an OpenAI (or OpenAI-compatible) {@link EmbeddingClient}.
     *
     * @return {@link OpenAiEmbeddingClient.Builder}
     */
    public static OpenAiEmbeddingClient.Builder openAi() {
        return OpenAiEmbeddingClient.builder();
    }

    /**
     * Returns a builder for a local Ollama {@link EmbeddingClient}.
     *
     * <p>Defaults to {@code http://localhost:11434} — override with
     * {@link OllamaEmbeddingClient.Builder#endpoint(String)} for remote instances.
     *
     * @return {@link OllamaEmbeddingClient.Builder}
     */
    public static OllamaEmbeddingClient.Builder ollama() {
        return OllamaEmbeddingClient.builder();
    }

    /**
     * Returns a builder for a Mistral AI {@link EmbeddingClient}.
     *
     * @return {@link MistralAiEmbeddingClient.Builder}
     */
    public static MistralAiEmbeddingClient.Builder mistral() {
        return MistralAiEmbeddingClient.builder();
    }
}
