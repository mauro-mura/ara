package io.ara.core.memory;

import java.util.Objects;

/**
 * Identifies the native tool call a {@code "tool"} or {@code "assistant_tool_call"}
 * {@link MemoryEntry} corresponds to, so adapters can reconstruct
 * {@code AiMessage(ToolExecutionRequest)} on the next LLM call.
 *
 * <p>Encoded as {@link MemoryEntry#metadata()} via {@link #encode()} / {@link #decode(String)}
 * since {@code MemoryEntry.metadata} is a plain string shared with other entry kinds
 * (e.g. episodic entry type).
 */
public record ToolCallMetadata(String callId, String toolName) {

    private static final String SEPARATOR = "\t";

    public ToolCallMetadata {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
    }

    public String encode() {
        return callId + SEPARATOR + toolName;
    }

    /** Decodes a string previously produced by {@link #encode()}; returns {@code null} for {@code null} input. */
    public static ToolCallMetadata decode(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(SEPARATOR, 2);
        return new ToolCallMetadata(parts[0], parts.length > 1 ? parts[1] : "");
    }
}
