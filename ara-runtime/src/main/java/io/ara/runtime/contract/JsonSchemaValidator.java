package io.ara.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.agent.processor.SchemaProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates that a payload is valid JSON and satisfies the declared schema.
 *
 * <p>Implements {@link SchemaProvider} when constructed via {@link #forOutput(String)},
 * allowing {@code AgentContract.outputSchema()} to read the schema without duplication:
 * <pre>{@code
 * JsonSchemaValidator validator = JsonSchemaValidator.forOutput(personSchema);
 *
 * AgentContract.builder()
 *     .outputSchema(validator)          // reads schema via SchemaProvider
 *     .addOutputProcessor(validator)    // same instance — schema declared once
 *     .build();
 * }</pre>
 *
 * <p>Can be used as both an {@link InputProcessor} and an {@link OutputProcessor}.
 */
public final class JsonSchemaValidator implements InputProcessor, OutputProcessor, SchemaProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String       rawSchema;     // null when constructed via requiring() / jsonOnly()
    private final List<String> requiredFields;

    private JsonSchemaValidator(String rawSchema, List<String> requiredFields) {
        this.rawSchema      = rawSchema;
        this.requiredFields = List.copyOf(Objects.requireNonNull(requiredFields));
    }

    /** Validates only that the payload is well-formed JSON. */
    public static JsonSchemaValidator jsonOnly() {
        return new JsonSchemaValidator(null, List.of());
    }

    /** Validates JSON well-formedness and the presence of the listed top-level fields. */
    public static JsonSchemaValidator requiring(String... fields) {
        return new JsonSchemaValidator(null, List.of(fields));
    }

    /**
     * Validates JSON well-formedness and required fields declared in the JSON Schema.
     * Also implements {@link SchemaProvider} so the same instance can be passed to
     * {@code AgentContract.Builder.outputSchema()}.
     */
    public static JsonSchemaValidator forOutput(String jsonSchema) {
        Objects.requireNonNull(jsonSchema, "jsonSchema must not be null");
        return new JsonSchemaValidator(jsonSchema, parseRequired(jsonSchema));
    }

    // ── SchemaProvider ────────────────────────────────────────────────────────

    @Override
    public String jsonSchema() {
        if (rawSchema == null) {
            throw new IllegalStateException(
                    "jsonSchema() requires a validator created via forOutput(String). " +
                    "Use JsonSchemaValidator.forOutput(schema) to enable SchemaProvider.");
        }
        return rawSchema;
    }

    // ── InputProcessor / OutputProcessor ─────────────────────────────────────

    @Override
    public ProcessingResult process(String payload) {
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return ProcessingResult.reject("Invalid JSON: " + e.getMessage());
        }
        List<String> missing = new ArrayList<>();
        for (String field : requiredFields) {
            if (!root.has(field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            return ProcessingResult.reject("Missing required fields: " + missing);
        }
        return ProcessingResult.pass(payload);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static List<String> parseRequired(String jsonSchema) {
        try {
            JsonNode schema = MAPPER.readTree(jsonSchema);
            JsonNode req = schema.get("required");
            if (req == null || !req.isArray()) return List.of();
            List<String> fields = new ArrayList<>();
            req.forEach(n -> fields.add(n.asText()));
            return fields;
        } catch (Exception e) {
            return List.of();
        }
    }
}
