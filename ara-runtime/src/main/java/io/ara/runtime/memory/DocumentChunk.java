package io.ara.runtime.memory;

/**
 * A single chunk produced by splitting a source document.
 *
 * @param docId      unique identifier of the source document
 * @param title      human-readable title of the source document
 * @param content    the text content of this chunk
 * @param chunkIndex zero-based index of this chunk within its document
 * @param score      cosine similarity score (0.0 for indexed chunks,
 *                   actual score for search results)
 */
public record DocumentChunk(
        String docId,
        String title,
        String content,
        int    chunkIndex,
        float  score
) {}