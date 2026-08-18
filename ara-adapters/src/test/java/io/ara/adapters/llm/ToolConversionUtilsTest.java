package io.ara.adapters.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises {@link ToolConversionUtils#toolSpecificationsFor} against the real langchain4j
 * schema types — the conversion's only consumer is the provider wire format, so asserting on
 * the produced {@link JsonObjectSchema} tree is what actually pins the behaviour down.
 *
 * <p>These cases exist because the parameter contract a tool advertises is invisible at
 * runtime: when the conversion understates it, the model simply sends the wrong arguments
 * and the failure surfaces as "the LLM is bad at calling this tool", never as a schema
 * error. Each test therefore asserts on the exact schema node rather than on a round trip.
 *
 * <p>Every test uses a distinct {@code toolId}: {@code toolSpecificationsFor} memoises
 * conversions in a process-wide cache keyed by tool id, so reusing an id across tests would
 * make one test observe another's converted schema.
 */
class ToolConversionUtilsTest {

    /** Minimal {@link AraTool} — only the schema matters here; {@link #execute} is never called. */
    private record SchemaOnlyTool(String toolId, String argumentSchema) implements AraTool {
        @Override public String description() { return "test tool"; }
        @Override public ToolResult execute(String argumentJson) {
            throw new UnsupportedOperationException("not part of the conversion under test");
        }
    }

    private static JsonObjectSchema parametersOf(String toolId, String argumentSchema) {
        LlmCallContext context = new LlmCallContext.Builder()
                .agentType("test")
                .resolvedTools(List.of(new SchemaOnlyTool(toolId, argumentSchema)))
                .build();

        List<ToolSpecification> specs = ToolConversionUtils.toolSpecificationsFor(context);
        assertEquals(1, specs.size());
        return assertInstanceOf(JsonObjectSchema.class, specs.get(0).parameters());
    }

    // ── required ──────────────────────────────────────────────────────────────

    @Test
    void keepsEveryRequiredNameOfANestedObject() {
        JsonObjectSchema root = parametersOf("test.nested_required", """
                {"type":"object",
                 "properties":{
                   "cfg":{"type":"object",
                          "properties":{"host":{"type":"string"},
                                        "port":{"type":"integer"},
                                        "user":{"type":"string"}},
                          "required":["host","port","user"]}},
                 "required":["cfg"]}
                """);

        JsonObjectSchema cfg = assertInstanceOf(JsonObjectSchema.class, root.properties().get("cfg"));
        assertEquals(List.of("host", "port", "user"), cfg.required());
    }

    @Test
    void keepsRequiredNamesAtEveryDepth() {
        JsonObjectSchema root = parametersOf("test.deep_required", """
                {"type":"object",
                 "properties":{
                   "outer":{"type":"object",
                            "properties":{
                              "inner":{"type":"object",
                                       "properties":{"a":{"type":"string"},"b":{"type":"string"}},
                                       "required":["a","b"]}},
                            "required":["inner"]}},
                 "required":["outer"]}
                """);

        JsonObjectSchema outer = assertInstanceOf(JsonObjectSchema.class, root.properties().get("outer"));
        JsonObjectSchema inner = assertInstanceOf(JsonObjectSchema.class, outer.properties().get("inner"));

        assertEquals(List.of("outer"), root.required());
        assertEquals(List.of("inner"), outer.required());
        assertEquals(List.of("a", "b"), inner.required());
    }

    // ── number vs integer ─────────────────────────────────────────────────────

    @Test
    void keepsArrayItemsOfTypeNumberFractional() {
        JsonObjectSchema root = parametersOf("test.number_array", """
                {"type":"object",
                 "properties":{"coordinates":{"type":"array","items":{"type":"number"}}},
                 "required":[]}
                """);

        JsonArraySchema coordinates = assertInstanceOf(JsonArraySchema.class,
                root.properties().get("coordinates"));
        assertInstanceOf(JsonNumberSchema.class, coordinates.items());
    }

    @Test
    void keepsNestedNumberPropertiesFractional() {
        JsonObjectSchema root = parametersOf("test.nested_number", """
                {"type":"object",
                 "properties":{
                   "box":{"type":"object",
                          "properties":{"width":{"type":"number"},"count":{"type":"integer"}}}},
                 "required":[]}
                """);

        JsonObjectSchema box = assertInstanceOf(JsonObjectSchema.class, root.properties().get("box"));
        assertInstanceOf(JsonNumberSchema.class, box.properties().get("width"));
        assertInstanceOf(JsonIntegerSchema.class, box.properties().get("count"));
    }

    @Test
    void keepsArrayItemsOfTypeIntegerWhole() {
        JsonObjectSchema root = parametersOf("test.integer_array", """
                {"type":"object",
                 "properties":{"ids":{"type":"array","items":{"type":"integer"}}},
                 "required":[]}
                """);

        JsonArraySchema ids = assertInstanceOf(JsonArraySchema.class, root.properties().get("ids"));
        assertInstanceOf(JsonIntegerSchema.class, ids.items());
    }

    @Test
    void keepsTopLevelNumberFractional() {
        JsonObjectSchema root = parametersOf("test.top_level_number", """
                {"type":"object",
                 "properties":{"threshold":{"type":"number"},"retries":{"type":"integer"}},
                 "required":[]}
                """);

        assertInstanceOf(JsonNumberSchema.class, root.properties().get("threshold"));
        assertInstanceOf(JsonIntegerSchema.class, root.properties().get("retries"));
    }

    // ── enum ──────────────────────────────────────────────────────────────────

    @Test
    void keepsTheDescriptionOfAnEnumProperty() {
        JsonObjectSchema root = parametersOf("test.enum_described", """
                {"type":"object",
                 "properties":{"mode":{"type":"string",
                                       "enum":["fast","thorough"],
                                       "description":"How hard to search"}},
                 "required":[]}
                """);

        JsonEnumSchema mode = assertInstanceOf(JsonEnumSchema.class, root.properties().get("mode"));
        assertEquals(List.of("fast", "thorough"), mode.enumValues());
        assertEquals("How hard to search", mode.description());
    }

    @Test
    void convertsAnEnumPropertyWithoutADescription() {
        JsonObjectSchema root = parametersOf("test.enum_bare", """
                {"type":"object",
                 "properties":{"mode":{"type":"string","enum":["fast","thorough"]}},
                 "required":[]}
                """);

        JsonEnumSchema mode = assertInstanceOf(JsonEnumSchema.class, root.properties().get("mode"));
        assertEquals(List.of("fast", "thorough"), mode.enumValues());
        assertNull(mode.description());
    }
}
