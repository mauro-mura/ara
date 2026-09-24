package io.ara.adapters.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import io.ara.core.memory.Reranker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * {@link Reranker} adapter over any <a href="https://github.com/langchain4j/langchain4j">
 * LangChain4j</a> {@link ScoringModel} (ADR-0088).
 *
 * <p>Unlike {@code OpenAiEmbeddingClient}/{@code OllamaEmbeddingClient}/{@code
 * MistralAiEmbeddingClient}, this adapter wraps a caller-supplied {@link ScoringModel}
 * rather than building one of its own: none of {@code langchain4j-open-ai}, {@code
 * langchain4j-ollama}, {@code langchain4j-anthropic} or {@code langchain4j-mistral-ai} —
 * ARA's four wired chat/embedding providers — ships a {@link ScoringModel} implementation, so
 * there is no provider-specific builder to add here. {@link ScoringModel} itself lives in
 * {@code langchain4j-core}, already a dependency for the embedding adapters, so wrapping it
 * costs nothing: whichever cross-encoder or reranking API a deployment adds (Cohere, Voyage,
 * a local ONNX cross-encoder, …) becomes an ARA {@link Reranker} by construction, the moment
 * its LangChain4j module is on the classpath and a {@link ScoringModel} instance from it is
 * passed to this constructor.
 *
 * <pre>{@code
 * ScoringModel  cohere   = CohereScoringModel.builder()....build();   // from langchain4j-cohere
 * Reranker      reranker = new LangChain4jReranker(cohere, "cohere-rerank-v3.5");
 * }</pre>
 */
public final class LangChain4jReranker implements Reranker {

    private final ScoringModel model;
    private final String       providerId;

    /** Uses {@code "langchain4j-scoring-model"} as {@link #providerId()}. */
    public LangChain4jReranker(ScoringModel model) {
        this(model, "langchain4j-scoring-model");
    }

    public LangChain4jReranker(ScoringModel model, String providerId) {
        this.model      = Objects.requireNonNull(model, "model must not be null");
        this.providerId = Objects.requireNonNull(providerId, "providerId must not be null");
    }

    @Override
    public List<RankedCandidate> rerank(String query, List<String> candidates) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = candidates.stream().map(TextSegment::from).toList();
        List<Double> scores = model.scoreAll(segments, query).content();
        if (scores == null || scores.size() != candidates.size()) {
            throw new IllegalStateException(providerId + ": scoreAll returned " +
                    (scores == null ? "no scores" : scores.size() + " scores") +
                    " for " + candidates.size() + " candidates");
        }
        List<RankedCandidate> ranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            ranked.add(new RankedCandidate(i, candidates.get(i), scores.get(i)));
        }
        ranked.sort(Comparator.comparingDouble(RankedCandidate::score).reversed());
        return ranked;
    }

    @Override
    public String providerId() {
        return providerId;
    }
}
