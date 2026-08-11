package io.ara.core.tool;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stateless utility for normalising tool-call payloads produced by LLM providers.
 *
 * <p>Different providers and models emit tool call requests in subtly different formats:
 * <ul>
 *   <li>Some prepend a namespace prefix to the tool name ({@code functions.my_tool}, {@code tool.my_tool}).</li>
 *   <li>Some pass the full JSON Schema as the arguments object instead of actual argument values.</li>
 *   <li>Some embed a tool-call JSON inside free text instead of using the native function-calling API.</li>
 * </ul>
 *
 * <p>All methods are pure static functions with no side effects. The class is not
 * instantiable.
 */
public final class ToolCallNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallNormalizer() {}

    // ── Tool ID normalisation ─────────────────────────────────────────────────

    /**
     * Strips a namespace prefix emitted by some models.
     *
     * <p>Examples: {@code "functions.get_time"} → {@code "get_time"},
     * {@code "tool.calculator"} → {@code "calculator"},
     * {@code "calculator"} → {@code "calculator"} (unchanged).
     *
     * @param toolId the raw tool identifier from the LLM response; may be {@code null}
     * @return the bare tool name, or the original value if no prefix is present
     */
    public static String stripNamespace(String toolId) {
        if (toolId == null) return null;
        int dot = toolId.lastIndexOf('.');
        return dot >= 0 ? toolId.substring(dot + 1) : toolId;
    }

    // ── Arguments normalisation ───────────────────────────────────────────────

    /**
     * Replaces a JSON Schema object passed as tool arguments with an empty object.
     *
     * <p>Some local models (e.g. Ollama, LM Studio with certain quantised models) pass
     * the tool's argument schema definition instead of actual argument values when
     * no arguments are needed. This method detects that pattern and returns {@code "{}"}.
     *
     * @param argumentJson the raw argument JSON string; may be {@code null} or blank
     * @return normalised argument JSON, never {@code null}
     */
    public static String normalizeArgs(String argumentJson) {
        if (argumentJson == null || argumentJson.isBlank()) return "{}";
        try {
            JsonNode node = MAPPER.readTree(argumentJson);
            if (isJsonSchema(node)) return "{}";
        } catch (Exception ignored) {}
        return argumentJson;
    }

    // ── Canonical tool-call JSON builder ─────────────────────────────────────

    /**
     * Builds the canonical ARA tool-call JSON: {@code {"tool_id":"…","arguments":{…}}}.
     *
     * @param toolId       raw tool identifier (namespace prefix will be stripped)
     * @param argumentsJson JSON string of arguments; {@code null} or blank treated as {@code {}}
     * @return an optional containing the canonical JSON, or empty if {@code toolId} is blank
     */
    public static Optional<String> toToolCallJson(String toolId, String argumentsJson) {
        if (isBlank(toolId)) return Optional.empty();
        try {
            JsonNode parsedArgs = MAPPER.readTree(
                    argumentsJson != null && !argumentsJson.isBlank() ? argumentsJson : "{}");
            if (!parsedArgs.isObject()) parsedArgs = MAPPER.createObjectNode();
            return toToolCallJson(toolId, parsedArgs);
        } catch (Exception ignored) {
            return toToolCallJson(toolId, (JsonNode) null);
        }
    }

    /**
     * Builds the canonical ARA tool-call JSON from a pre-parsed {@link JsonNode}.
     *
     * @param toolId   raw tool identifier (namespace prefix will be stripped)
     * @param argsNode parsed arguments node; {@code null} treated as empty object
     * @return an optional containing the canonical JSON, or empty if {@code toolId} is blank
     */
    public static Optional<String> toToolCallJson(String toolId, JsonNode argsNode) {
        String id = stripNamespace(toolId);
        if (isBlank(id)) return Optional.empty();

        ObjectNode root = MAPPER.createObjectNode();
        root.put("tool_id", id);

        JsonNode args = argsNode;
        // Unwrap double-wrapped args: {"arguments":{…}} → {…}
        if (args != null && args.isObject() && args.has("arguments") && args.size() == 1) {
            args = args.get("arguments");
        }
        root.set("arguments", (args != null && args.isObject() && !isJsonSchema(args))
                ? args : MAPPER.createObjectNode());

        try {
            return Optional.of(MAPPER.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    // ── Embedded text extraction ──────────────────────────────────────────────

    /**
     * Scans free text for a JSON object that looks like a tool call and returns the
     * canonical ARA tool-call JSON if one is found.
     *
     * <p>Handles the common instruction-following formats:
     * <ul>
     *   <li>{@code {"tool_id":"calculator","arguments":{…}}}</li>
     *   <li>{@code {"action":"calculator","action_input":{…}}} (ReAct format)</li>
     *   <li>{@code {"name":"calculator","parameters":{…}}}</li>
     * </ul>
     *
     * @param text the raw LLM response text; may be {@code null}
     * @return an optional containing the canonical tool-call JSON, or empty if none found
     */
    public static Optional<String> extractFromText(String text) {
        if (text == null || text.isBlank()) return Optional.empty();

        String cleaned = text.replaceAll("```(?:json)?\\s*", "").replace("```", "").trim();
        int start = 0;
        while ((start = cleaned.indexOf('{', start)) != -1) {
            int end = findMatchingBrace(cleaned, start);
            if (end == -1) { start++; continue; }
            Optional<String> normalized = tryParseEmbedded(cleaned.substring(start, end + 1));
            if (normalized.isPresent()) return normalized;
            start++;
        }
        return Optional.empty();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static Optional<String> tryParseEmbedded(String jsonCandidate) {
        try {
            JsonNode node = MAPPER.readTree(jsonCandidate);

            String toolId = textValue(node, "tool_id");
            JsonNode argsNode = firstPresent(node, "arguments", "args");

            if (isBlank(toolId)) {
                toolId = textValue(node, "action");
                if (argsNode == null) argsNode = node.get("action_input");
            }
            if (isBlank(toolId)) {
                toolId = textValue(node, "name");
                if (argsNode == null) argsNode = firstPresent(node, "parameters", "arguments");
            }

            if (isBlank(toolId)) return Optional.empty();
            return toToolCallJson(toolId, argsNode);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static int findMatchingBrace(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return i; }
        }
        return -1;
    }

    private static boolean isJsonSchema(JsonNode node) {
        return node != null && node.isObject() && node.has("type") && node.has("properties");
    }

    private static JsonNode firstPresent(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null) return v;
        }
        return null;
    }

    private static String textValue(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
