package io.ara.core.llm;

import java.util.Objects;

/**
 * A single message in the conversation history sent to an {@link LlmClient}.
 *
 * @param role        the speaker role: {@code "system"}, {@code "user"}, {@code "assistant"},
 *                    {@code "assistant_tool_call"}, or {@code "tool"}
 * @param content     the text content (or tool arguments JSON for {@code "assistant_tool_call"})
 * @param toolCallId  OpenAI tool-call id — non-null for {@code "assistant_tool_call"} and
 *                    {@code "tool"} roles; {@code null} for all other roles
 * @param toolName    the tool name — non-null for {@code "assistant_tool_call"} and {@code "tool"}
 *                    roles; {@code null} for all other roles
 */
public record LlmMessage(String role, String content, String toolCallId, String toolName) {

    public LlmMessage {
        Objects.requireNonNull(role,    "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    /** Backward-compatible 2-arg constructor — {@code toolCallId} and {@code toolName} default to {@code null}. */
    public LlmMessage(String role, String content) {
        this(role, content, null, null);
    }

    /** Creates a system-role message (the agent's system prompt). */
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    /** Creates a user-role message (the human's input or a plain text tool observation). */
    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    /** Creates an assistant-role message (a previous LLM response in the history). */
    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }

    /**
     * Creates an assistant message that represents a native OpenAI tool call.
     * The {@code argsJson} is the raw JSON arguments; {@code toolCallId} and {@code toolName}
     * are needed to pair this message with the corresponding {@link #tool} result.
     */
    public static LlmMessage assistantToolCall(String toolCallId, String toolName, String argsJson) {
        return new LlmMessage("assistant_tool_call",
                argsJson != null ? argsJson : "{}",
                toolCallId, toolName);
    }

    /**
     * Creates a tool-result message for native OpenAI function calling.
     * Must be preceded in the conversation history by an {@link #assistantToolCall} with the
     * same {@code toolCallId}.
     */
    public static LlmMessage tool(String toolCallId, String toolName, String result) {
        return new LlmMessage("tool",
                result != null ? result : "",
                toolCallId, toolName);
    }
}
