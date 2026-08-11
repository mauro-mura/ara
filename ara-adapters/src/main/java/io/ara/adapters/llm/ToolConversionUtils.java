package io.ara.adapters.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.*;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.tool.AraTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Utility class for converting ARA tool schemas to LangChain4j {@link ToolSpecification} objects.
 *
 * <p>ARA tools expose their parameter contract as a JSON Schema string via
 * {@link io.ara.core.tool.AraTool#argumentSchema()}. This class parses that schema and
 * produces the {@link ToolSpecification} required by the LangChain4j chat models when
 * native function-calling is enabled.
 *
 * <p>Supported JSON Schema types: {@code string}, {@code integer}, {@code number},
 * {@code boolean}, {@code array} and {@code object} (nested). Unknown types fall back
 * to {@code string}.
 *
 * <p>Usage inside an adapter:
 * <pre>{@code
 * if (context.hasResolvedTools()) {
 *     chatRequestBuilder.toolSpecifications(ToolConversionUtils.toolSpecificationsFor(context));
 * }
 * }</pre>
 */
public final class ToolConversionUtils {

    private static final Logger log = LoggerFactory.getLogger(ToolConversionUtils.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Cache of converted {@link ToolSpecification}s, keyed by the sorted, comma-joined tool ids
     * of a call's {@code resolvedTools()}. Process-wide and shared by every adapter (OpenAI,
     * Anthropic, ...) rather than duplicated per adapter instance: the conversion is
     * provider-agnostic — {@link ToolSpecification} is the same langchain4j type regardless of
     * which chat model consumes it — so there is no reason to repeat the (moderately expensive)
     * JSON-Schema-tree build, or keep a separate cache of it, once per adapter class.
     *
     * <p>Tool ids are expected to be stable identifiers with a fixed schema across the runtime —
     * the same assumption every {@code ToolRegistry} already makes — so widening the sharing
     * scope from "one {@code LlmClient} instance" to "the whole JVM" does not introduce a new
     * correctness risk.
     */
    private static final Map<String, List<ToolSpecification>> TOOL_SPEC_CACHE = new ConcurrentHashMap<>();

    private ToolConversionUtils() {}

    /**
     * Converts {@code context.resolvedTools()} to langchain4j {@link ToolSpecification}s,
     * cached by the sorted set of tool ids so repeated ReAct/plan-execute iterations with the
     * same enabled-tools list don't repeat the schema conversion.
     *
     * @param context the call context; only call this when {@link LlmCallContext#hasResolvedTools()}
     * @return the tool specifications, in the same order as {@code resolvedTools()}
     */
    public static List<ToolSpecification> toolSpecificationsFor(LlmCallContext context) {
        List<AraTool> tools = context.resolvedTools();
        String cacheKey = tools.stream()
                .map(AraTool::toolId)
                .sorted()
                .collect(Collectors.joining(","));

        return TOOL_SPEC_CACHE.computeIfAbsent(cacheKey, k -> tools.stream()
                .map(FunctionSpec::fromAraTool)
                .map(ToolConversionUtils::convertFunctionToToolSpec)
                .collect(Collectors.toList()));
    }

    // ── Request-side conversion (ARA conversation history → langchain4j) ────

    /**
     * Reconstructs the langchain4j {@link ChatMessage} for one ARA {@link LlmMessage},
     * restoring native tool-call / tool-result structure for the {@code "assistant_tool_call"},
     * {@code "assistant_tool_calls"} and {@code "tool"} roles instead of collapsing them into a
     * generic {@link UserMessage} turn.
     *
     * <p>Shared by every adapter that speaks native provider function-calling
     * ({@code LlmClient.supportsNativeTools() == true}), so the reconstruction contract lives in
     * exactly one place rather than being reimplemented — and silently drifting — per adapter.
     *
     * @param m the ARA message to convert
     * @return the equivalent langchain4j {@link ChatMessage}
     */
    public static ChatMessage toNativeAwareChatMessage(LlmMessage m) {
        return switch (m.role()) {
            case "system" -> SystemMessage.from(m.content());
            case "assistant" -> AiMessage.from(m.content());
            case "assistant_tool_call" -> AiMessage.from(List.of(
                    ToolExecutionRequest.builder()
                            .id(m.toolCallId())
                            .name(m.toolName())
                            .arguments(m.content())
                            .build()));
            case "assistant_tool_calls" -> AiMessage.from(parseParallelToolCallsJson(m.content()));
            case "tool" -> ToolExecutionResultMessage.from(m.toolCallId(), m.toolName(), m.content());
            default -> UserMessage.from(m.content() != null ? m.content() : "");
        };
    }

    /**
     * Parses the JSON array {@code ReactStrategy} serialises for a parallel tool-call batch
     * ({@code [{"id":"...","name":"...","args":"..."}, ...]}) into langchain4j {@link
     * ToolExecutionRequest}s, preserving call order. Package-visible detail of {@link
     * #toNativeAwareChatMessage}'s {@code "assistant_tool_calls"} branch — the id/name of each
     * call live inside this JSON, not on the {@link LlmMessage} itself, since a single {@code
     * LlmMessage} can only carry one {@code toolCallId}/{@code toolName} pair.
     *
     * @param json the JSON array of {@code {id, name, args}} entries
     * @return the parsed requests, in the same order as the JSON array
     * @throws IllegalArgumentException if {@code json} cannot be parsed in the expected shape
     */
    public static List<ToolExecutionRequest> parseParallelToolCallsJson(String json) {
        try {
            List<Map<String, String>> raw = MAPPER.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(e -> ToolExecutionRequest.builder()
                            .id(e.get("id"))
                            .name(e.get("name"))
                            .arguments(e.get("args"))
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed assistant_tool_calls JSON: " + json, e);
        }
    }

    // ── Response-side conversion (provider tool calls → ARA) ─────────────────

    /**
     * Converts the LangChain4j tool-execution requests of one completion into ARA
     * {@link ToolCallEntry} rows, preserving order and per-call ids. This is the
     * multi-call path: adapters must map <em>every</em> request, not just the first —
     * OpenAI parallel function-calling and Anthropic multi-{@code tool_use} responses
     * routinely carry several. Null/blank arguments become {@code "{}"}.
     */
    public static List<ToolCallEntry> toToolCallEntries(List<ToolExecutionRequest> requests) {
        return requests.stream()
                .map(r -> new ToolCallEntry(r.id(), r.name(), argumentsOrEmpty(r)))
                .collect(Collectors.toList());
    }

    /**
     * Builds the legacy single-call JSON {@code {"tool_id":"...","arguments":{...}}}
     * from one request, escaping the tool name. Populates
     * {@link io.ara.core.llm.LlmCompletion#toolCallJson()} for backward compatibility
     * with consumers that predate the {@code toolCalls} list.
     */
    public static String toLegacyToolCallJson(ToolExecutionRequest request) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"tool_id\":\"");
        escapeJsonInto(sb, request.name());
        sb.append("\",\"arguments\":").append(argumentsOrEmpty(request)).append('}');
        return sb.toString();
    }

    private static String argumentsOrEmpty(ToolExecutionRequest request) {
        String args = request.arguments();
        return (args != null && !args.isBlank()) ? args : "{}";
    }

    private static void escapeJsonInto(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < ' ') sb.append(String.format("\\u%04x", (int) c));
                    else         sb.append(c);
                }
            }
        }
    }

    /**
     * Converts a single {@link FunctionSpec} to a LangChain4j {@link ToolSpecification}.
     * Internal detail of {@link #toolSpecificationsFor} — {@link FunctionSpec} is not part of
     * this class's public contract.
     *
     * @param func the ARA function specification to convert
     * @return the corresponding LangChain4j tool specification
     */
    private static ToolSpecification convertFunctionToToolSpec(FunctionSpec func) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(func.name())
                .description(func.description());

        if (func.parameters() != null && !func.parameters().isEmpty()) {
            JsonObjectSchema schema = convertParametersToJsonSchema(func.parameters(), func.requiredParameters());
            builder.parameters(schema);
        }

        return builder.build();
    }

    // ── Internal conversion helpers ───────────────────────────────────────────

    private static JsonObjectSchema convertParametersToJsonSchema(
            Map<String, Object> params, List<String> requiredParams) {

        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

        if (params.containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");
            properties.forEach((name, def) -> addPropertyToSchema(schemaBuilder, name, def));
        }

        schemaBuilder.required(requiredParams);
        return schemaBuilder.build();
    }

    private static void addPropertyToSchema(JsonObjectSchema.Builder builder, String name, Object propDef) {
        if (!(propDef instanceof Map)) {
            builder.addStringProperty(name);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> prop = (Map<String, Object>) propDef;
        String type        = (String) prop.getOrDefault("type", "string");
        String description = (String) prop.get("description");

        switch (type) {
            case "string" -> {
                if (prop.containsKey("enum")) {
                    @SuppressWarnings("unchecked")
                    List<String> enumValues = (List<String>) prop.get("enum");
                    builder.addEnumProperty(name, enumValues);
                } else if (description != null) {
                    builder.addStringProperty(name, description);
                } else {
                    builder.addStringProperty(name);
                }
            }
            case "integer" -> {
                if (description != null) builder.addIntegerProperty(name, description);
                else                     builder.addIntegerProperty(name);
            }
            case "number" -> {
                if (description != null) builder.addNumberProperty(name, description);
                else                     builder.addNumberProperty(name);
            }
            case "boolean" -> {
                if (description != null) builder.addBooleanProperty(name, description);
                else                     builder.addBooleanProperty(name);
            }
            case "array" -> {
                JsonSchemaElement items = prop.containsKey("items")
                        ? convertPropertyToJsonSchemaElement(prop.get("items")) : null;
                JsonArraySchema.Builder ab = JsonArraySchema.builder();
                if (description != null) ab.description(description);
                if (items != null)       ab.items(items);
                builder.addProperty(name, ab.build());
            }
            case "object" -> builder.addProperty(name, buildObjectSchema(prop));
            default -> {
                if (description != null) builder.addStringProperty(name, description);
                else                     builder.addStringProperty(name);
            }
        }
    }

    private static JsonSchemaElement convertPropertyToJsonSchemaElement(Object propDef) {
        if (!(propDef instanceof Map)) {
            return JsonStringSchema.builder().build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> prop = (Map<String, Object>) propDef;
        String type        = (String) prop.getOrDefault("type", "string");
        String description = (String) prop.get("description");

        return switch (type) {
            case "string"          -> JsonStringSchema.builder().description(description).build();
            case "integer","number"-> JsonIntegerSchema.builder().description(description).build();
            case "boolean"         -> JsonBooleanSchema.builder().description(description).build();
            case "array"           -> buildArraySchema(prop, description);
            case "object"          -> buildObjectSchema(prop);
            default                -> JsonStringSchema.builder().description(description).build();
        };
    }

    private static JsonArraySchema buildArraySchema(Map<String, Object> prop, String description) {
        JsonArraySchema.Builder builder = JsonArraySchema.builder().description(description);
        if (prop.containsKey("items")) {
            builder.items(convertPropertyToJsonSchemaElement(prop.get("items")));
        }
        return builder.build();
    }

    private static JsonObjectSchema buildObjectSchema(Map<String, Object> prop) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        if (prop.containsKey("description")) {
            builder.description((String) prop.get("description"));
        }
        if (prop.containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) prop.get("properties");
            properties.forEach((name, def) -> addPropertyToSchema(builder, name, def));
        }
        if (prop.containsKey("required")) {
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) prop.get("required");
            required.forEach(builder::required);
        }
        return builder.build();
    }

    // ── Value object ──────────────────────────────────────────────────────────

    /**
     * Lightweight descriptor of an ARA function/tool for schema conversion purposes. Internal
     * detail of {@link #toolSpecificationsFor} — not part of this class's public contract, since
     * nothing outside this class needs to go through this intermediate shape.
     *
     * @param name               the tool identifier (must be a valid function name)
     * @param description        human-readable description shown to the model
     * @param parameters         JSON Schema {@code object} map describing the parameters
     * @param requiredParameters list of required parameter names
     */
    private record FunctionSpec(
            String name,
            String description,
            Map<String, Object> parameters,
            List<String> requiredParameters
    ) {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        /**
         * Creates a {@link FunctionSpec} from an {@link AraTool} by parsing its JSON Schema string.
         *
         * <p>A parse failure degrades to an empty parameter set rather than propagating — one bad
         * tool schema must not stop every other tool (or the LLM call itself) from working — but
         * is logged at WARN, naming the offending tool id, since silently swallowing it previously
         * made a malformed {@code argumentSchema()} (e.g. an unescaped quote in a parameter
         * description breaking the JSON) indistinguishable from a tool that legitimately takes no
         * parameters — see the tool-schema-without-parameters class of bugs this was found from.
         *
         * @param tool the ARA tool to convert
         * @return a {@link FunctionSpec} ready for {@link #convertFunctionToToolSpec}
         */
        @SuppressWarnings("unchecked")
        static FunctionSpec fromAraTool(AraTool tool) {
            Map<String, Object> schema = Map.of();
            List<String> required = List.of();
            String schemaJson = tool.argumentSchema();
            if (schemaJson != null && !schemaJson.isBlank()) {
                try {
                    Map<String, Object> parsed = MAPPER.readValue(
                            schemaJson, new TypeReference<>() {});
                    schema   = parsed;
                    Object req = parsed.get("required");
                    if (req instanceof List<?> list) {
                        required = (List<String>) list;
                    }
                } catch (Exception e) {
                    log.warn("Tool '{}' has a malformed argumentSchema() — falling back to no "
                            + "parameters. Raw schema: {}", tool.toolId(), schemaJson, e);
                }
            }
            return new FunctionSpec(tool.toolId(), tool.description(), schema, required);
        }
    }
}
