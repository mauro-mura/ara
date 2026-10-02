package io.ara.core.memory;

import java.util.List;

/**
 * Converts text into a dense float vector suitable for semantic similarity search.
 *
 * <p>Implementations must be thread-safe. Each call to {@link #embed} is
 * independent and produces no side effects.
 *
 * <p>The returned vector must always have exactly {@link #dimensions()} elements.
 * Callers use {@link #dimensions()} at startup to configure the vector store.
 */
public interface EmbeddingClient {

    /**
     * Produces a normalised dense vector for the given text.
     *
     * @param text the text to embed; must not be blank
     * @return a list of {@link #dimensions()} float values
     * @throws RuntimeException if the embedding call fails
     */
    List<Float> embed(String text);

    /**
     * Produces one vector per input text, in the same order as {@code texts}.
     *
     * <p>Defaulted to a sequential loop over {@link #embed(String)} so every existing
     * implementation (demo clients, test doubles) keeps working untouched. Real provider
     * adapters should override it to issue a single batched request: the OpenAI, Mistral and
     * Ollama embeddings APIs all accept an array of inputs, so a document of N chunks costs one
     * round trip instead of N. The ordering guarantee is what lets a caller zip the returned
     * vectors back onto the texts it passed — an override MUST preserve it.
     *
     * @param texts the texts to embed; none may be blank
     * @return a list of vectors, one per input, each of {@link #dimensions()} elements, in input order
     * @throws RuntimeException if the embedding call fails
     */
    default List<List<Float>> embedAll(List<String> texts) {
        List<List<Float>> vectors = new java.util.ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    /**
     * Returns the fixed dimension of all vectors produced by this client.
     * Must be consistent across calls and match the vector store configuration.
     *
     * @return vector dimension (e.g. 1536 for text-embedding-ada-002,
     *         1536 for text-embedding-3-small, 3072 for text-embedding-3-large)
     */
    int dimensions();

    /**
     * Identifies this client for logging and diagnostics — e.g. {@code "openai-text-embedding-3-small"}.
     *
     * <p>Defaulted rather than required so every existing implementation (demo clients,
     * test doubles) keeps compiling; a real adapter should override it the same way
     * {@code LlmClient#providerId()} adapters do, and {@code io.ara.adapters.embedding.EmbeddingEndpointPool}
     * uses it to name which endpoint served a call or failed.
     *
     * @return a short, human-readable identifier for this client
     */
    default String providerId() {
        return "embedding";
    }
}