package io.ara.core.llm;

import io.ara.core.media.MediaRef;

import java.util.List;
import java.util.Objects;

/**
 * A single message in the conversation history sent to an {@link LlmClient}.
 *
 * <h2>Media travels beside the text, not inside it</h2>
 * <p>{@link #media} is a peer of {@link #content}, and holds references rather than bytes.
 * The alternative — adopting a provider SDK's list-of-content-parts shape as the domain
 * type — would tie {@code ara-core} to one SDK and force every {@code LlmClient}, including
 * stubs, decorators and non-SDK clients, to reason in parts even when the provider
 * underneath has none. Flattening into parts is the adapter's job.
 *
 * <p>Keeping media as a peer list does lose positional information: "this text, then
 * <em>this</em> image, then that text" cannot be expressed inside one message. That is
 * acceptable because the real shape of these requests is a short prompt plus one or more
 * attachments, not prose interleaving ten images — and a caller that genuinely needs
 * fine-grained interleaving can split the content across consecutive {@code user} messages,
 * whose order the message list already preserves. So {@code MediaRef} carries no position
 * field. What the peer list <em>does</em> require is one fixed flattening convention, or
 * each adapter would invent its own: the text comes first, then the media in list order.
 * {@code ToolConversionUtils} in {@code ara-adapters} applies it in a single place.
 *
 * @param role        the speaker role: {@code "system"}, {@code "user"}, {@code "assistant"},
 *                    {@code "assistant_tool_call"}, or {@code "tool"}
 * @param content     the text content (or tool arguments JSON for {@code "assistant_tool_call"})
 * @param toolCallId  OpenAI tool-call id — non-null for {@code "assistant_tool_call"} and
 *                    {@code "tool"} roles; {@code null} for all other roles
 * @param toolName    the tool name — non-null for {@code "assistant_tool_call"} and {@code "tool"}
 *                    roles; {@code null} for all other roles
 * @param media       images and documents accompanying this message, in presentation order;
 *                    never {@code null}, and only meaningful for the {@code "user"} role —
 *                    no provider accepts attachments on a system or tool-result message
 */
public record LlmMessage(String role, String content, String toolCallId, String toolName,
                         List<MediaRef> media) {

    public LlmMessage {
        Objects.requireNonNull(role,    "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        media = media != null ? List.copyOf(media) : List.of();
    }

    /**
     * Text-only 4-arg constructor.
     *
     * <p>Kept as an overload rather than left to callers of the canonical constructor
     * because adding {@code media} changed that constructor's arity: this keeps every
     * existing {@code new LlmMessage(role, content, toolCallId, toolName)} call site
     * compiling and behaving identically.
     */
    public LlmMessage(String role, String content, String toolCallId, String toolName) {
        this(role, content, toolCallId, toolName, List.of());
    }

    /** Backward-compatible 2-arg constructor — {@code toolCallId} and {@code toolName} default to {@code null}. */
    public LlmMessage(String role, String content) {
        this(role, content, null, null, List.of());
    }

    /** Creates a system-role message (the agent's system prompt). */
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    /** Creates a user-role message (the human's input or a plain text tool observation). */
    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    /**
     * Creates a user-role message carrying media. {@code content} may be blank — a task
     * that is only a document has no words of its own to send.
     */
    public static LlmMessage user(String content, List<MediaRef> media) {
        return new LlmMessage("user", content, null, null, media);
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

    /** {@code true} when this message carries at least one media reference. */
    public boolean hasMedia() {
        return !media.isEmpty();
    }
}
