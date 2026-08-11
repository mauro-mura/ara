package io.ara.runtime.agent;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentFuture;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Merge strategies for {@link AgentFuture#allOf} that require Jackson.
 *
 * <p>The strategies without external dependencies ({@code rawJsonConcat},
 * {@code firstWins}, {@code joining}) live in {@link AgentChain.MergeStrategy}.
 * This class contains only Jackson-dependent strategies, keeping {@code ara-core}
 * free of external dependencies (ADR-016 fix C16).
 */
public final class MergeStrategies {

    private static final ObjectMapper JSON = new ObjectMapper();   // shared, thread-safe
    private static final JsonFactory FACTORY = JSON.getFactory();  // shared, thread-safe

    private MergeStrategies() {}

    /**
     * Like {@link AgentChain.MergeStrategy#rawJsonConcat()} but attempts to parse
     * each output: non-JSON values are wrapped as quoted JSON strings.
     * Always produces a well-formed JSON array.
     */
    public static AgentChain.MergeStrategy safeJsonArray() {
        return responses -> {
            String joined = responses.stream()
                    .map(r -> {
                        String content = r.content();
                        return isWellFormedJson(content) ? content.strip() : toJsonString(content);
                    })
                    .collect(Collectors.joining(",", "[", "]"));
            return AgentChain.aggregateSuccess(joined, responses);
        };
    }

    /**
     * Returns {@code true} if {@code value} is exactly one well-formed JSON value.
     *
     * <p>Streams the tokens instead of {@code readTree}-ing them: validity is the only
     * question being asked, so materialising the whole {@code JsonNode} graph — for LLM
     * output that can be megabytes — just to throw it away is wasted allocation and GC
     * pressure. {@code skipChildren()} walks the structure without building it.
     *
     * <p>The trailing {@code nextToken() == null} check also closes a real hole in the
     * {@code readTree} version: {@code readTree} stops at the first complete value and
     * ignores whatever follows, so {@code {"a":1} oops} was classified as valid JSON and
     * concatenated raw, producing a malformed array.
     */
    private static boolean isWellFormedJson(String value) {
        if (value == null || value.isBlank()) return false;
        try (JsonParser parser = FACTORY.createParser(value)) {
            if (parser.nextToken() == null) return false;   // no value at all
            parser.skipChildren();                          // walk, don't materialise
            return parser.nextToken() == null;              // reject trailing content
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Quotes {@code value} as a JSON string literal via Jackson, rather than hand-rolled
     * escaping — a manual backslash/quote replace misses control characters (e.g. the
     * newlines routine in LLM output), which would otherwise produce malformed JSON.
     */
    private static String toJsonString(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to JSON-encode a plain string", e);
        }
    }
}
