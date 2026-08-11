package io.ara.core.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * A document stored in a {@link KnowledgeBase}.
 *
 * <p>The original {@code content} is persisted in H2 so the system can
 * re-index automatically when the embedding model changes.
 *
 * @param docId      unique identifier within the knowledge base
 * @param kbId       parent knowledge base id
 * @param title      display title shown in search results
 * @param content    full document text (original, un-chunked)
 * @param indexedAt  timestamp of the last successful indexing
 */
public record KbDocument(
        String docId,
        String kbId,
        String title,
        String content,
        Instant indexedAt
) {
    public KbDocument {
        Objects.requireNonNull(docId,     "docId must not be null");
        Objects.requireNonNull(kbId,      "kbId must not be null");
        Objects.requireNonNull(title,     "title must not be null");
        Objects.requireNonNull(content,   "content must not be null");
        Objects.requireNonNull(indexedAt, "indexedAt must not be null");
    }
}