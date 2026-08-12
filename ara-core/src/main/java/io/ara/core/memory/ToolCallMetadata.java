package io.ara.core.memory;

import java.util.Objects;

/**
 * Identifies the native tool call a {@code "tool"} or {@code "assistant_tool_call"}
 * {@link MemoryEntry} corresponds to, so adapters can reconstruct
 * {@code AiMessage(ToolExecutionRequest)} on the next LLM call.
 *
 * <p>Carried directly as {@link MemoryEntry#metadata()} — no string encoding involved.
 */
public record ToolCallMetadata(String callId, String toolName) implements EntryMetadata {

    public ToolCallMetadata {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
    }
}
