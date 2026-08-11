package io.ara.core.llm;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result returned by {@link LlmClient#complete}.
 *
 * <p>Supports both legacy single-call responses (via {@link #toolCallJson} /
 * {@link #toolCallId}) and multi-call responses from providers that return several
 * tool invocations in one completion (via {@link #toolCalls}).  The two
 * representations are mutually consistent: if {@code toolCalls} is non-empty it
 * takes precedence; the legacy fields are kept for backward compatibility with
 * provider adapters that have not yet been updated.
 *
 * @param text          the generated text from the LLM
 * @param promptTokens  number of tokens in the prompt
 * @param outputTokens  number of tokens in the generated response
 * @param finishReason  why the LLM stopped: {@code "stop"}, {@code "length"}, {@code "tool_calls"}, etc.
 * @param toolCallJson  raw JSON of a single tool call request (legacy); may be null
 * @param toolCallId    the provider-specific id for the single tool call (legacy); may be null
 * @param toolCalls     ordered list of all tool calls in this completion; empty for text responses
 * @param tokensEstimated {@code true} when {@code promptTokens}/{@code outputTokens} are an
 *                        approximation (currently: {@code ReactStrategy}'s streaming fallback,
 *                        which has no real usage data and estimates ~4 chars/token) rather than
 *                        provider-reported exact counts. {@code false} for every other path.
 */
public record LlmCompletion(
        String       text,
        int          promptTokens,
        int          outputTokens,
        String       finishReason,
        String       toolCallJson,
        String       toolCallId,
        List<io.ara.core.llm.ToolCallEntry> toolCalls,
        boolean      tokensEstimated
) {

    public LlmCompletion {
        Objects.requireNonNull(text,         "text must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
    }

    /** Backward-compatible 7-arg constructor — {@code tokensEstimated} defaults to {@code false} (exact counts). */
    public LlmCompletion(String text, int promptTokens, int outputTokens,
                         String finishReason, String toolCallJson, String toolCallId,
                         List<io.ara.core.llm.ToolCallEntry> toolCalls) {
        this(text, promptTokens, outputTokens, finishReason, toolCallJson, toolCallId, toolCalls, false);
    }

    /** Backward-compatible 6-arg constructor — {@code toolCalls} defaults to empty, {@code tokensEstimated} to {@code false}. */
    public LlmCompletion(String text, int promptTokens, int outputTokens,
                         String finishReason, String toolCallJson, String toolCallId) {
        this(text, promptTokens, outputTokens, finishReason, toolCallJson, toolCallId, List.of(), false);
    }

    /** Backward-compatible 5-arg constructor — {@code toolCallId} and {@code toolCalls} default to null/empty. */
    public LlmCompletion(String text, int promptTokens, int outputTokens,
                         String finishReason, String toolCallJson) {
        this(text, promptTokens, outputTokens, finishReason, toolCallJson, null, List.of(), false);
    }

    /** Total tokens consumed by this completion (prompt + output). */
    public int totalTokens() {
        return promptTokens + outputTokens;
    }

    /**
     * Returns {@code true} if the LLM requested at least one tool call.
     * Checks both the multi-call list and the legacy single-call field.
     */
    public boolean hasToolCall() {
        return !toolCalls.isEmpty() || (toolCallJson != null && !toolCallJson.isBlank());
    }

    /** Returns the tool-call JSON wrapped in an {@link Optional}. */
    public Optional<String> toolCallJsonOpt() {
        return (toolCallJson != null && !toolCallJson.isBlank())
                ? Optional.of(toolCallJson) : Optional.empty();
    }

    /** Returns the OpenAI tool-call id wrapped in an {@link Optional}. */
    public Optional<String> toolCallIdOpt() {
        return toolCallId != null && !toolCallId.isBlank()
                ? Optional.of(toolCallId) : Optional.empty();
    }
}
