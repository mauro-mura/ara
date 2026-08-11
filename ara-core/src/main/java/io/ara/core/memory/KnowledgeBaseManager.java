package io.ara.core.memory;

import java.util.List;
import java.util.Optional;

/**
 * Business operations for managing knowledge bases and their documents.
 *
 * <p>This interface lives in {@code ara-core} so that transport layers (e.g. REST handlers
 * in {@code ara-gateway}) can manage knowledge bases without depending on any runtime
 * implementation class. The concrete implementation is
 * {@code io.ara.runtime.memory.KnowledgeBaseService}.
 *
 * <p>Implementations delegate CRUD persistence to a {@link KnowledgeBaseRepository} and
 * vector indexing to a {@link SemanticStore} (via an {@link EmbeddingClient} for chunk
 * embedding). Neither dependency is visible through this interface.
 */
public interface KnowledgeBaseManager {

    /** Creates a new knowledge base with the given embedding configuration (defaults to qdrant store). */
    KnowledgeBase create(String kbId, String name, EmbeddingConfig embedCfg);

    /** Creates a new knowledge base with an explicit store type ({@code "qdrant"} or {@code "in_memory"}). */
    KnowledgeBase create(String kbId, String name, EmbeddingConfig embedCfg, String storeType);

    /** Creates a new knowledge base with full retrieval parameter control. */
    KnowledgeBase create(String kbId, String name, EmbeddingConfig embedCfg, String storeType,
                         int defaultMaxResults, float scoreThreshold);

    /** Returns all knowledge bases, or empty list if none exist. */
    List<KnowledgeBase> listAll();

    /** Finds a knowledge base by id, or empty if not found. */
    Optional<KnowledgeBase> find(String kbId);

    /** Deletes the knowledge base and its associated resources. */
    void delete(String kbId);

    /** Replaces the embedding model for a knowledge base and re-indexes all documents. */
    KnowledgeBase updateEmbedding(String kbId, EmbeddingConfig newCfg);

    /** Updates retrieval parameters without touching the embedding model or documents. */
    KnowledgeBase updateRetrieval(String kbId, int defaultMaxResults, float scoreThreshold);

    /**
     * Indexes a document into the knowledge base.
     *
     * @return the number of chunks created
     */
    int indexDocument(String kbId, String docId, String title, String content);

    /** Returns all documents stored in the knowledge base. */
    List<KbDocument> listDocuments(String kbId);

    /**
     * Deletes a document from the knowledge base.
     *
     * @return {@code true} if the document was found and removed
     */
    boolean deleteDocument(String kbId, String docId);
}
