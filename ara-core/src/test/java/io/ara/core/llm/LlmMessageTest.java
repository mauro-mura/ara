package io.ara.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The reasoning a tool-call message can carry back to the model. */
class LlmMessageTest {

    @Test
    void aToolCall_keepsTheReasoningItIsGiven() {
        LlmMessage call = LlmMessage.assistantToolCall("c1", "write_file", "{}", "Need to write the file.");

        assertEquals("Need to write the file.", call.reasoning());
        assertEquals("assistant_tool_call", call.role());
    }

    @Test
    void blankReasoning_isNoReasoning() {
        // A null or empty field sent back is not harmless: llama.cpp's gpt-oss template fails on a null.
        assertNull(LlmMessage.assistantToolCall("c1", "t", "{}", "  ").reasoning());
        assertNull(LlmMessage.assistantToolCall("c1", "t", "{}", null).reasoning());
    }

    @Test
    void theShapesBeforeReasoning_haveNone() {
        assertNull(LlmMessage.assistantToolCall("c1", "t", "{}").reasoning());
        assertNull(new LlmMessage("user", "hi", null, null, List.of()).reasoning());
        assertNull(LlmMessage.user("hi").reasoning());
    }
}
