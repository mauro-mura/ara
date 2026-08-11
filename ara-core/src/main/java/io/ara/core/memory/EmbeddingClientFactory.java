package io.ara.core.memory;

/**
 * Factory that creates an {@link EmbeddingClient} from an {@link EmbeddingConfig}.
 * Implemented in {@code ara-llm-langchain4j} and injected into ara-runtime via the composition root.
 */
@FunctionalInterface
public interface EmbeddingClientFactory {
    EmbeddingClient create(EmbeddingConfig config);
}