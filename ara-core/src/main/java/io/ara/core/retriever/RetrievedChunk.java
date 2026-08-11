package io.ara.core.retriever;

/**
 * A single passage returned by a {@link Retriever} for a given query.
 *
 * @param docId   identifier of the source document
 * @param title   human-readable title of the source document
 * @param content the text of the passage
 * @param score   similarity score in [0, 1] — higher is more relevant
 */
public record RetrievedChunk(String docId, String title, String content, double score) {}
