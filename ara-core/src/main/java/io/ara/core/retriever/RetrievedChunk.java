package io.ara.core.retriever;

/**
 * A single passage returned by a {@link Retriever} for a given query.
 *
 * <p>A retriever whose pipeline does not report a per-passage score (it only returns an
 * already-ranked list) passes {@link #UNSCORED} instead of inventing one: consumers then
 * rely on the list order and do not show a number that suggests a precision the pipeline
 * never measured.
 *
 * @param docId   identifier of the source document
 * @param title   human-readable title of the source document
 * @param content the text of the passage
 * @param score   similarity score in [0, 1] — higher is more relevant — or {@link #UNSCORED}
 */
public record RetrievedChunk(String docId, String title, String content, double score) {

    /** Score of a passage whose retriever reports only the rank, not a relevance value. */
    public static final double UNSCORED = Double.NaN;

    /** Whether {@link #score()} is a real relevance value rather than {@link #UNSCORED}. */
    public boolean hasScore() {
        return !Double.isNaN(score);
    }
}
