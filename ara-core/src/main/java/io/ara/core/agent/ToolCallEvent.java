package io.ara.core.agent;

import java.util.Objects;

/**
 * A tool call about to be dispatched, delivered to {@link AgentTask#toolCallCallback()} just
 * before execution so a caller (e.g. a streaming UI) can show which tool is running and with
 * what arguments — the SSE {@code tool_call} event and Secpac's chat "tool chip" are both
 * built from this.
 *
 * <p>A small dedicated type rather than a raw string or an anonymous pair: {@link
 * ExecutionStep#toolCall} already models this same tool-id/arguments shape for the execution
 * trace, and encoding both into one string (as an ad hoc JSON envelope) only to decode it again
 * downstream is exactly the fragility this replaces.
 *
 * @param toolId       the tool identifier
 * @param argumentJson JSON-serialised arguments, or {@code null} when the tool takes none
 */
public record ToolCallEvent(String toolId, String argumentJson) {

    public ToolCallEvent {
        Objects.requireNonNull(toolId, "toolId must not be null");
    }
}
