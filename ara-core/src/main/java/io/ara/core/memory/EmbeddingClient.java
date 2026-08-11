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
     * Returns the fixed dimension of all vectors produced by this client.
     * Must be consistent across calls and match the vector store configuration.
     *
     * @return vector dimension (e.g. 1536 for text-embedding-ada-002,
     *         1536 for text-embedding-3-small, 3072 for text-embedding-3-large)
     */
    int dimensions();
}