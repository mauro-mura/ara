package io.ara.adapters.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Request-side mapping in {@link ToolConversionUtils#toNativeAwareChatMessage}: every ARA
 * conversation-history role must be reconstructed into its native langchain4j counterpart.
 *
 * <p>Before this fix, {@code OpenAiLlmClient}/{@code AnthropicLlmClient}'s {@code
 * toLC4jMessages} only recognised {@code "system"}/{@code "assistant"} and collapsed
 * {@code "assistant_tool_call"}, {@code "assistant_tool_calls"} and {@code "tool"} into a
 * generic {@link UserMessage} — silently discarding {@code toolCallId}/{@code toolName} even
 * though {@link LlmMessage} carries them for exactly this purpose. That meant every multi-turn
 * tool exchange was sent back to the provider as plain text instead of the structured
 * assistant-tool-call / tool-result pairing native function-calling expects.
 */
class ToolConversionUtilsNativeMessageTest {

    @Test
    void system_becomesSystemMessage() {
        ChatMessage m = ToolConversionUtils.toNativeAwareChatMessage(LlmMessage.system("be helpful"));
        assertInstanceOf(SystemMessage.class, m);
        assertEquals("be helpful", ((SystemMessage) m).text());
    }

    @Test
    void assistant_becomesAiMessage_withNoToolCalls() {
        ChatMessage m = ToolConversionUtils.toNativeAwareChatMessage(LlmMessage.assistant("hi there"));
        assertInstanceOf(AiMessage.class, m);
        AiMessage ai = (AiMessage) m;
        assertEquals("hi there", ai.text());
        assertFalse(ai.hasToolExecutionRequests());
    }

    @Test
    void plainUnknownRole_fallsBackToUserMessage() {
        ChatMessage m = ToolConversionUtils.toNativeAwareChatMessage(LlmMessage.user("plain text"));
        assertInstanceOf(UserMessage.class, m);
        assertEquals("plain text", ((UserMessage) m).singleText());
    }

    @Test
    void assistantToolCall_becomesAiMessage_withOneToolExecutionRequest() {
        LlmMessage src = LlmMessage.assistantToolCall("call_1", "search", "{\"q\":\"x\"}");

        ChatMessage m = ToolConversionUtils.toNativeAwareChatMessage(src);

        assertInstanceOf(AiMessage.class, m);
        AiMessage ai = (AiMessage) m;
        assertTrue(ai.hasToolExecutionRequests());
        assertEquals(1, ai.toolExecutionRequests().size());
        ToolExecutionRequest req = ai.toolExecutionRequests().get(0);
        assertEquals("call_1", req.id());
        assertEquals("search", req.name());
        assertEquals("{\"q\":\"x\"}", req.arguments());
    }

    @Test
    void tool_becomesToolExecutionResultMessage_withMatchingIdAndName() {
        LlmMessage src = LlmMessage.tool("call_1", "search", "3 results found");

        ChatMessage m = ToolConversionUtils.toNativeAwareChatMessage(src);

        assertInstanceOf(ToolExecutionResultMessage.class, m);
        ToolExecutionResultMessage tr = (ToolExecutionResultMessage) m;
        assertEquals("call_1", tr.id());
        assertEquals("search", tr.toolName());
        assertEquals("3 results found", tr.text());
    }

    @Test
    void assistantToolCalls_parallelBatch_becomesAiMessage_withAllRequestsInOrder() {
        String json = """
                [{"id":"c1","name":"search","args":"{\\"q\\":\\"a\\"}"},\
                {"id":"c2","name":"read","args":"{\\"path\\":\\"f.txt\\"}"}]""";
        LlmMessage src = new LlmMessage("assistant_tool_calls", json);

        ChatMessage m = ToolConversionUtils.toNativeAwareChatMessage(src);

        assertInstanceOf(AiMessage.class, m);
        List<ToolExecutionRequest> requests = ((AiMessage) m).toolExecutionRequests();
        assertEquals(2, requests.size());
        assertEquals("c1", requests.get(0).id());
        assertEquals("search", requests.get(0).name());
        assertEquals("{\"q\":\"a\"}", requests.get(0).arguments());
        assertEquals("c2", requests.get(1).id());
        assertEquals("read", requests.get(1).name());
        assertEquals("{\"path\":\"f.txt\"}", requests.get(1).arguments());
    }

    @Test
    void parseParallelToolCallsJson_malformed_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolConversionUtils.parseParallelToolCallsJson("not json"));
    }
}
