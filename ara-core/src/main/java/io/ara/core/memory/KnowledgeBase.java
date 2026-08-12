package io.ara.core.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable descriptor of a knowledge base — a named, independently-configured
 * RAG document store.
 *
 * @param kbId              unique identifier (e.g. {@code "kb-it-support"})
 * @param name              human-readable display name
 * @param embeddingConfig   embedding model settings used for indexing and search
 * @param qdrantCollection  name of the Qdrant collection (ignored for in-memory stores)
 * @param createdAt         creation timestamp
 * @param storeType         backing store: {@code "qdrant"} (default) or {@code "in_memory"}
 * @param defaultMaxResults default number of chunks returned by search_documents (1–20, default 3)
 * @param scoreThreshold    minimum cosine similarity score for a chunk to be returned (default 0.7)
 */
public record KnowledgeBase(
        String kbId,
        String name,
        EmbeddingConfig embeddingConfig,
        String qdrantCollection,
        Instant createdAt,
        String storeType,
        int defaultMaxResults,
        float scoreThreshold
) {
    public static final String STORE_QDRANT    = "qdrant";
    public static final String STORE_IN_MEMORY = "in_memory";

    public static final int   DEFAULT_MAX_RESULTS  = 3;
    public static final float DEFAULT_SCORE_THRESHOLD = 0.7f;

    public KnowledgeBase {
        Objects.requireNonNull(kbId,            "kbId must not be null");
        Objects.requireNonNull(name,            "name must not be null");
        Objects.requireNonNull(embeddingConfig, "embeddingConfig must not be null");
        Objects.requireNonNull(qdrantCollection,"qdrantCollection must not be null");
        Objects.requireNonNull(createdAt,       "createdAt must not be null");
        if (kbId.isBlank())  throw new IllegalArgumentException("kbId must not be blank");
        if (name.isBlank())  throw new IllegalArgumentException("name must not be blank");
        // storeType is an open registry key — which stores actually exist is decided by
        // whoever resolves one at wiring time (as ExecutionPlanner does for
        // plannerStrategy, and RetrieverRouter for retrieverId), not by ara-core. Only
        // "unspecified" is handled here.
        if (storeType == null || storeType.isBlank()) storeType = STORE_QDRANT;
        if (defaultMaxResults < 1 || defaultMaxResults > 20)
            throw new IllegalArgumentException("defaultMaxResults must be between 1 and 20");
        if (scoreThreshold < 0f || scoreThreshold > 1f)
            throw new IllegalArgumentException("scoreThreshold must be in [0.0, 1.0]");
    }

    /** Convenience constructor — defaults to Qdrant store, 3 results, no score filter. */
    public KnowledgeBase(String kbId, String name, EmbeddingConfig embeddingConfig,
                         String qdrantCollection, Instant createdAt) {
        this(kbId, name, embeddingConfig, qdrantCollection, createdAt,
                STORE_QDRANT, DEFAULT_MAX_RESULTS, DEFAULT_SCORE_THRESHOLD);
    }

    /** Convenience constructor — specifies store type, defaults for retrieval params. */
    public KnowledgeBase(String kbId, String name, EmbeddingConfig embeddingConfig,
                         String qdrantCollection, Instant createdAt, String storeType) {
        this(kbId, name, embeddingConfig, qdrantCollection, createdAt,
                storeType, DEFAULT_MAX_RESULTS, DEFAULT_SCORE_THRESHOLD);
    }

    public boolean isInMemory() { return STORE_IN_MEMORY.equals(storeType); }

    /** Derives the Qdrant collection name from the kb id and vector dimensions. */
    public static String collectionName(String kbId, int dimensions) {
        return kbId + "_" + dimensions + "d";
    }
}
