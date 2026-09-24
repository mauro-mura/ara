package io.ara.core.memory;

import java.util.List;

/**
 * Reorders a list of retrieved candidates by relevance to a query — the rerank stage of the
 * recall pipeline, and the counterpart to {@link EmbeddingClient} for that stage (ADR-0088).
 *
 * <p>An {@link EmbeddingClient} answers "how similar is this text to the query", computed
 * independently per text and cheap to precompute at index time. A reranker answers "how
 * relevant is this text to the query", scored with the query and the candidate together —
 * more expensive, so it runs only on the short list an embedding search already narrowed
 * down, never on a whole store.
 *
 * <p>Implementations must be thread-safe. Each call to {@link #rerank} is independent and
 * produces no side effects.
 */
public interface Reranker {

    /**
     * Scores {@code candidates} against {@code query} and returns them sorted by descending
     * relevance.
     *
     * @param query      the cue text candidates are scored against; must not be blank
     * @param candidates the texts to rerank; an empty list returns an empty list
     * @return exactly {@code candidates.size()} {@link RankedCandidate} values, sorted by
     *         descending {@link RankedCandidate#score()}
     * @throws RuntimeException if the reranking call fails
     */
    List<RankedCandidate> rerank(String query, List<String> candidates);

    /**
     * One reranked candidate.
     *
     * @param originalIndex the candidate's index in the {@code candidates} list passed to
     *                       {@link #rerank}, so a caller can map back to whatever richer
     *                       object (e.g. a {@link MemoryEntry}) that text stood in for
     * @param text          the candidate text, unchanged
     * @param score         the model's relevance score; higher is more relevant, and only
     *                       meaningfully comparable to other scores from the same call
     */
    record RankedCandidate(int originalIndex, String text, double score) {}

    /**
     * Identifies this reranker for logging and diagnostics — e.g. {@code "scoring-model"}.
     *
     * <p>Defaulted, the same way {@link EmbeddingClient#providerId()} is, so every existing
     * implementation keeps compiling.
     *
     * @return a short, human-readable identifier for this reranker
     */
    default String providerId() {
        return "reranker";
    }
}
