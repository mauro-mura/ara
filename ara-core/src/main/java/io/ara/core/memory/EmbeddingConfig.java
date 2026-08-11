package io.ara.core.memory;

import java.util.Objects;

/**
 * Immutable configuration for an embedding model endpoint.
 *
 * @param baseUrl    base URL of the OpenAI-compatible embeddings API
 * @param apiKey     API key (may be a dummy value for local servers)
 * @param model      embedding model name (e.g. {@code text-embedding-3-small})
 * @param dimensions vector dimension — must match the Qdrant collection
 */
public record EmbeddingConfig(
        String baseUrl,
        String apiKey,
        String model,
        int dimensions
) {
    public EmbeddingConfig {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(apiKey,  "apiKey must not be null");
        Objects.requireNonNull(model,   "model must not be null");
        if (baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
        if (model.isBlank())   throw new IllegalArgumentException("model must not be blank");
        if (dimensions < 1)    throw new IllegalArgumentException("dimensions must be >= 1");
    }
}