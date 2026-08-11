package io.ara.runtime.memory;

import io.ara.core.retriever.Retriever;

import java.util.Map;

/**
 * Common interface for knowledge-base backing stores (Qdrant and in-memory).
 */
public interface KbStore extends Retriever {

    void ensureCollection();

    int indexDocument(String docId, String title, String content);

    boolean deleteDocument(String docId);

    Map<String, String> listDocuments();
}
