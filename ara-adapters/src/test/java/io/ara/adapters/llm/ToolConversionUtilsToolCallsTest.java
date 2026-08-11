package io.ara.adapters.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.ara.core.llm.ToolCallEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Response-side mapping in {@link ToolConversionUtils}: every tool-execution request in
 * a completion must survive the conversion — this is what makes parallel tool calls
 * actually work through the OpenAI/Anthropic adapters (previously only the first
 * request was mapped and the rest were silently dropped).
 */
class ToolConversionUtilsToolCallsTest {

    private static ToolExecutionRequest req(String id, String name, String args) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(args).build();
    }

    @Test
    void allRequests_mapped_inOrder_withIds() {
        List<ToolCallEntry> entries = ToolConversionUtils.toToolCallEntries(List.of(
                req("call_1", "search",  "{\"q\":\"a\"}"),
                req("call_2", "read",    "{\"path\":\"f.txt\"}"),
                req("call_3", "compile", "{}")));

        assertEquals(3, entries.size());
        assertEquals(new ToolCallEntry("call_1", "search",  "{\"q\":\"a\"}"),      entries.get(0));
        assertEquals(new ToolCallEntry("call_2", "read",    "{\"path\":\"f.txt\"}"), entries.get(1));
        assertEquals(new ToolCallEntry("call_3", "compile", "{}"),                 entries.get(2));
    }

    @Test
    void nullOrBlankArguments_becomeEmptyObject() {
        List<ToolCallEntry> entries = ToolConversionUtils.toToolCallEntries(List.of(
                req("c1", "no_args", null),
                req("c2", "blank_args", "  ")));

        assertEquals("{}", entries.get(0).argumentJson());
        assertEquals("{}", entries.get(1).argumentJson());
    }

    @Test
    void nullToolCallId_isPreserved() {
        // Some OpenAI-compatible endpoints omit ids — the entry must not invent one.
        List<ToolCallEntry> entries = ToolConversionUtils.toToolCallEntries(List.of(
                req(null, "search", "{}")));
        assertNull(entries.get(0).toolCallId());
    }

    @Test
    void legacyJson_wrapsFirstCall_inAraFormat() {
        String json = ToolConversionUtils.toLegacyToolCallJson(
                req("call_1", "search", "{\"q\":\"x\"}"));
        assertEquals("{\"tool_id\":\"search\",\"arguments\":{\"q\":\"x\"}}", json);
    }

    @Test
    void legacyJson_escapesToolName() {
        String json = ToolConversionUtils.toLegacyToolCallJson(
                req("c1", "we\"ird\\name", null));
        assertEquals("{\"tool_id\":\"we\\\"ird\\\\name\",\"arguments\":{}}", json);
    }
}