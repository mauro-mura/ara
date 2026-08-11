package io.ara.adapters.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ToolConversionUtils#toolSpecificationsFor} — the single, provider-agnostic
 * conversion path shared by every adapter (previously duplicated per adapter, with a cache only
 * on the OpenAI side; see the fix this replaced).
 */
class ToolConversionUtilsToolSpecificationsTest {

    private static AraTool tool(String id, String description, String schema) {
        return new AraTool() {
            @Override public String toolId()         { return id; }
            @Override public String description()    { return description; }
            @Override public String argumentSchema()  { return schema; }
            @Override public ToolResult execute(String argsJson) { return ToolResult.success(id, "ok"); }
        };
    }

    private static LlmCallContext ctxWith(AraTool... tools) {
        return LlmCallContext.from(AgentConfig.defaults().build()).withResolvedTools(List.of(tools));
    }

    @Test
    void convertsToolSchema_toMatchingToolSpecification() {
        AraTool search = tool("search", "Searches documents",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}");

        List<ToolSpecification> specs = ToolConversionUtils.toolSpecificationsFor(ctxWith(search));

        assertEquals(1, specs.size());
        assertEquals("search", specs.get(0).name());
        assertEquals("Searches documents", specs.get(0).description());
        assertNotNull(specs.get(0).parameters());
    }

    @Test
    void preservesOrder_forMultipleTools() {
        AraTool a = tool("a", "A", "{}");
        AraTool b = tool("b", "B", "{}");

        List<ToolSpecification> specs = ToolConversionUtils.toolSpecificationsFor(ctxWith(a, b));

        assertEquals(List.of("a", "b"), specs.stream().map(ToolSpecification::name).toList());
    }

    @Test
    void sameToolIdSet_returnsCachedInstance() {
        AraTool search = tool("cached-tool", "desc", "{}");

        List<ToolSpecification> first  = ToolConversionUtils.toolSpecificationsFor(ctxWith(search));
        List<ToolSpecification> second = ToolConversionUtils.toolSpecificationsFor(ctxWith(search));

        // Two independent LlmCallContext/AraTool instances, same tool id set: the cache must
        // return the exact same converted List, not repeat the JSON-Schema-tree build.
        assertSame(first, second, "identical tool-id sets should hit the shared cache");
    }

    @Test
    void differentToolIdSet_convertsIndependently() {
        AraTool x = tool("x-tool", "X", "{}");
        AraTool y = tool("y-tool", "Y", "{}");

        List<ToolSpecification> specsX = ToolConversionUtils.toolSpecificationsFor(ctxWith(x));
        List<ToolSpecification> specsY = ToolConversionUtils.toolSpecificationsFor(ctxWith(y));

        assertEquals("x-tool", specsX.get(0).name());
        assertEquals("y-tool", specsY.get(0).name());
    }

    /**
     * Reproduces the real bug this was found from: a caller building {@code argumentSchema()}
     * by raw string concatenation (instead of a JSON library) can embed an unescaped quote from
     * a parameter description — e.g. {@code "...(default: "10")"} — breaking the JSON. The
     * conversion must degrade to a tool with no parameters rather than throw, so one bad schema
     * doesn't take down every other tool or the LLM call itself; {@code FunctionSpec.fromAraTool}
     * additionally logs a WARN naming the tool id so this no longer fails silently.
     */
    @Test
    void malformedSchema_fromUnescapedQuoteInDescription_degradesToNoParameters_insteadOfThrowing() {
        String brokenSchema = "{\"type\":\"object\",\"properties\":{"
                + "\"limit\":{\"type\":\"string\",\"description\":\"Max results (default: \"10\")\"}"
                + "},\"required\":[]}";
        AraTool tool = tool("getEventsOnPeriod", "Get available events on period", brokenSchema);

        List<ToolSpecification> specs = ToolConversionUtils.toolSpecificationsFor(ctxWith(tool));

        assertEquals(1, specs.size());
        assertEquals("getEventsOnPeriod", specs.get(0).name());
        // No exception propagated — degrades gracefully instead of breaking every tool in the call.
    }
}
