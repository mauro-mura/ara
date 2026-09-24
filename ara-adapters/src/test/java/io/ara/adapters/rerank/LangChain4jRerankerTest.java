package io.ara.adapters.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import io.ara.core.memory.Reranker;
import io.ara.core.memory.Reranker.RankedCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-0088: {@link LangChain4jReranker} scores every candidate via the wrapped
 * {@link ScoringModel} and returns them sorted by descending score, tagged with their
 * original index. A hand-written fake stands in for the model — no mocking framework, same
 * convention as {@code SlidingWindowMemoryManagerContextTest}'s {@code RecordingStore}.
 */
class LangChain4jRerankerTest {

    /** Fake {@link ScoringModel}: scores each segment by a fixed lookup table on its text. */
    private static final class FixedScoreModel implements ScoringModel {
        private final Map<String, Double> scoresByText;

        FixedScoreModel(Map<String, Double> scoresByText) {
            this.scoresByText = scoresByText;
        }

        @Override
        public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
            List<Double> scores = segments.stream()
                    .map(seg -> scoresByText.getOrDefault(seg.text(), 0.0))
                    .toList();
            return Response.from(scores);
        }
    }

    @Test
    void rerank_sortsCandidatesByDescendingScore_preservingOriginalIndex() {
        FixedScoreModel model = new FixedScoreModel(Map.of(
                "low", 0.1,
                "high", 0.9,
                "mid", 0.5
        ));
        Reranker reranker = new LangChain4jReranker(model);

        List<RankedCandidate> ranked = reranker.rerank("q", List.of("low", "high", "mid"));

        assertEquals(3, ranked.size());
        assertEquals("high", ranked.get(0).text());
        assertEquals(1, ranked.get(0).originalIndex());
        assertEquals("mid", ranked.get(1).text());
        assertEquals(2, ranked.get(1).originalIndex());
        assertEquals("low", ranked.get(2).text());
        assertEquals(0, ranked.get(2).originalIndex());
    }

    @Test
    void rerank_emptyCandidates_returnsEmpty() {
        Reranker reranker = new LangChain4jReranker(new FixedScoreModel(Map.of()));

        assertEquals(List.of(), reranker.rerank("q", List.of()));
    }

    @Test
    void providerId_defaultsAndIsOverridable() {
        FixedScoreModel model = new FixedScoreModel(Map.of());

        assertEquals("langchain4j-scoring-model", new LangChain4jReranker(model).providerId());
        assertEquals("cohere-rerank-v3.5",
                new LangChain4jReranker(model, "cohere-rerank-v3.5").providerId());
    }
}
