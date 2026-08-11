package io.ara.core.llm;

import java.util.Objects;

/**
 * A single tool-call entry inside a multi-call {@link LlmCompletion}.
 *
 * <p>Provider adapters populate this when the LLM returns several tool
 * invocations in one response (e.g. OpenAI parallel function-calling,
 * Anthropic tool_use blocks).  The {@link ReactStrategy} dispatches all
 * entries in parallel via virtual threads.
 *
 * @param toolCallId   provider-specific call id (e.g. OpenAI {@code tool_call_id}); may be null
 * @param toolId       normalised tool identifier (ARA format, dot-prefix stripped)
 * @param argumentJson JSON-serialised arguments matching the tool's input schema
 */
public record ToolCallEntry(String toolCallId, String toolId, String argumentJson) {
    public ToolCallEntry {
        Objects.requireNonNull(toolId,       "toolId must not be null");
        Objects.requireNonNull(argumentJson, "argumentJson must not be null");
    }
}
