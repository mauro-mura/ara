package io.ara.core.memory;

import java.util.List;

/**
 * Port for a vector-based semantic memory store.
 *
 * <p>Implementations may target Qdrant, Weaviate, pgvector, or any other vector
 * database. The concrete Qdrant implementation lives in {@code ara-runtime}.
 *
 * <p>All operations are scoped by {@code agentId} so multiple agents can share
 * a single store instance without data leakage.
 *
 * <p>This port is consumed by {@link MemoryManager} implementations, which embed
 * text via an {@link EmbeddingClient} before calling {@link #upsert} or {@link #search}.
 */
public interface SemanticStore {

    /**
     * Inserts or updates a memory vector for the given agent.
     *
     * @param agentId the owning agent's identifier
     * @param role    the memory role (e.g. {@code "episode"}, {@code "assistant"})
     * @param type    a sub-type label for the entry (may be {@code null})
     * @param content the original text content to associate with the vector
     * @param vector  the embedding vector produced by an {@link EmbeddingClient}
     */
    void upsert(String agentId, String role, String type, String content, List<Float> vector);

    /**
     * Searches for the most semantically similar entries for the given agent.
     *
     * @param agentId     the owning agent's identifier
     * @param queryVector the query embedding produced by an {@link EmbeddingClient}
     * @param limit       maximum number of results to return
     * @return a ranked list of matching memory entries; never {@code null}
     */
    List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit);
}
