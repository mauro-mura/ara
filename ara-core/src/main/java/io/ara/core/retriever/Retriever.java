package io.ara.core.retriever;

import java.util.List;

/**
 * Port for semantic document retrieval used by {@code RetrievalAugmentedStrategy}.
 *
 * <p>A {@code Retriever} encapsulates the full retrieval pipeline: embedding the query,
 * running nearest-neighbour search, and returning ranked passages. Callers need not know
 * whether the backing store is Qdrant, Pinecone, an in-memory index, or a mock.
 *
 * <p>Implementations in {@code ara-runtime}:
 * <ul>
 *   <li>{@code DocumentStore} — Qdrant-backed, used for knowledge-base RAG</li>
 * </ul>
 *
 * <p>Usage in strategy composition:
 * <pre>{@code
 * Retriever retriever = new DocumentStore(qdrantConfig, embeddingClient);
 *
 * ExecutionStrategy ragReact =
 *         RetrievalAugmentedStrategy.wrap(new ReactStrategy(), retriever);
 * }</pre>
 */
public interface Retriever {

    /**
     * Retrieves the most relevant passages for the given query.
     *
     * @param query      natural-language search query
     * @param maxResults maximum number of passages to return
     * @return ranked list of passages, most relevant first; never {@code null}
     */
    List<RetrievedChunk> retrieve(String query, int maxResults);
}
