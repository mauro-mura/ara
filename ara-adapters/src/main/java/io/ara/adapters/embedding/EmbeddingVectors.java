package io.ara.adapters.embedding;

import java.util.List;

import io.ara.core.memory.EmbeddingException;

/**
 * Validates that a provider actually returned the vector size the client promises via
 * {@link io.ara.core.memory.EmbeddingClient#dimensions()}.
 *
 * <p>Shared across the {@code openai}, {@code ollama} and {@code mistral} adapters because a
 * silent dimension mismatch is exactly the failure mode this whole feature exists to catch: an
 * OpenAI or Ollama model can be asked to truncate to a given dimension and comply, but Mistral's
 * embeddings API accepts no such parameter at all — a client misconfigured with the wrong
 * {@code dimensions()} would otherwise hand a wrongly-sized vector to a vector store configured
 * for a fixed dimension, corrupting it silently instead of failing loudly here.
 */
public final class EmbeddingVectors {

    private EmbeddingVectors() {}

    /**
     * @return {@code vector}, unchanged, if its size matches {@code expectedDimensions}
     * @throws EmbeddingException if the sizes disagree
     */
    public static List<Float> validate(String provider, String modelName, List<Float> vector,
                                        int expectedDimensions) {
        if (vector.size() != expectedDimensions) {
            throw EmbeddingException.invalidRequest(provider, String.format(
                    "Embedding model '%s' returned a %d-dimensional vector but this client is "
                            + "configured for %d — check the builder's dimensions(int)",
                    modelName, vector.size(), expectedDimensions));
        }
        return vector;
    }
}
