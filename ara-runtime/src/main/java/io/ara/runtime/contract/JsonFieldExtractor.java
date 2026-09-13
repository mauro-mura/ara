package io.ara.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

/**
 * Extracts a single field from the agent's JSON output.
 *
 * <p>The field is specified as a simple dot-path (e.g. {@code "result.content"}).
 * The extracted value is returned as its JSON string representation; plain strings
 * are returned without enclosing quotes.
 */
public final class JsonFieldExtractor implements InputProcessor, OutputProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String fieldPath;

    private JsonFieldExtractor(String fieldPath) {
        this.fieldPath = fieldPath;
    }

    public static JsonFieldExtractor field(String path) {
        return new JsonFieldExtractor(path);
    }

    @Override
    public ProcessingResult process(String output) {
        JsonNode root;
        try {
            root = MAPPER.readTree(output);
        } catch (Exception e) {
            return ProcessingResult.reject("Failed to parse output as JSON: " + e.getMessage());
        }

        JsonNode node = JsonFieldNavigator.navigate(root, fieldPath);
        if (node == null) {
            return ProcessingResult.reject("Field '" + fieldPath + "' not found in output");
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        return ProcessingResult.pass(value);
    }
}
