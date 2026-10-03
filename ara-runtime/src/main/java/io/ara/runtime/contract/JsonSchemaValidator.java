package io.ara.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.agent.processor.SchemaProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *
 * <h2>What {@link #forOutput(String)} checks</h2>
 * The full JSON Schema, through networknt {@code json-schema-validator}: {@code type},
 * nested {@code properties} and {@code items}, {@code required}, {@code enum}/{@code const},
 * numeric and length bounds, {@code pattern}, {@code additionalProperties},
 * {@code oneOf}/{@code anyOf}/{@code allOf}, local {@code $ref}. The dialect is the one the
 * schema names in {@code $schema}, draft 2020-12 when it names none. Until this validator
 * moved onto a real engine it checked only well-formedness and the top-level
 * {@code required} list, so a payload that used to pass loosely can now be rejected — that
 * is the point of the change, but it is a behavioural change for existing contracts.
 *
 * <p>Four choices that differ from the library's defaults, all for the same reason — the
 * payload is usually LLM output, and the rejection reason is usually read back by an LLM
 * (see {@code AgentContract.outputRepairAttempts()}):
 * <ul>
 *   <li><b>{@code format} is asserted</b>, not merely annotated: a schema author who writes
 *       {@code "format":"date"} for a model's output wants a non-date rejected.</li>
 *   <li><b>Paths are JSON Path</b> ({@code $.items[2].price}), not JSON Pointer, whose root
 *       is the empty string and reads as nothing in a message.</li>
 *   <li><b>Messages are always English</b>, whatever the JVM locale, so a rejection reason
 *       is stable across machines and in tests.</li>
 *   <li><b>Remote {@code $ref} is never fetched</b> (the 2.x default, kept deliberately):
 *       a schema read from a catalog must not make the runtime issue HTTP requests.</li>
 * </ul>
 *
 * <p>A rejection lists every violation with its path — {@code $.items[2].price: string
 * found, number expected} — up to {@value #MAX_REPORTED_ERRORS}, followed by how many more
 * were cut. The same violations also travel as {@link ProcessingResult.Reject#issues()}
 * (path + message, up to {@value #MAX_ISSUES}), for callers that need them as data.
 */
public final class JsonSchemaValidator implements InputProcessor, OutputProcessor, SchemaProvider {

    /** Cap on violations spelled out in one rejection reason; the rest are only counted. */
    static final int MAX_REPORTED_ERRORS = 10;

    /** Cap on the structured issues attached to a rejection; the reason text is capped lower. */
    static final int MAX_ISSUES = 50;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .locale(Locale.ENGLISH)
                    .pathType(PathType.JSON_PATH)
                    .formatAssertionsEnabled(true)
                    .build()));

    private final String       rawSchema;     // null when constructed via requiring() / jsonOnly()
    private final Schema       schema;        // null when constructed via requiring() / jsonOnly()
    private final List<String> requiredFields;

    private JsonSchemaValidator(String rawSchema, Schema schema, List<String> requiredFields) {
        this.rawSchema      = rawSchema;
        this.schema         = schema;
        this.requiredFields = List.copyOf(Objects.requireNonNull(requiredFields));
    }

    /** Validates only that the payload is well-formed JSON. */
    public static JsonSchemaValidator jsonOnly() {
        return new JsonSchemaValidator(null, null, List.of());
    }

    /** Validates JSON well-formedness and the presence of the listed top-level fields. */
    public static JsonSchemaValidator requiring(String... fields) {
        return new JsonSchemaValidator(null, null, List.of(fields));
    }

    /**
     * Validates the payload against the full JSON Schema {@code jsonSchema}.
     * Also implements {@link SchemaProvider} so the same instance can be passed to
     * {@code AgentContract.Builder.outputSchema()}.
     *
     * @throws IllegalArgumentException if {@code jsonSchema} is not valid JSON or not a valid
     *                                  schema — at construction, rather than as a puzzling
     *                                  rejection of every payload on the first task
     */
    public static JsonSchemaValidator forOutput(String jsonSchema) {
        Objects.requireNonNull(jsonSchema, "jsonSchema must not be null");
        return new JsonSchemaValidator(jsonSchema, compile(jsonSchema), List.of());
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
        if (root == null || root.isMissingNode()) {
            return ProcessingResult.reject("Invalid JSON: empty payload");
        }
        if (schema != null) {
            List<com.networknt.schema.Error> errors = schema.validate(root);
            return errors.isEmpty()
                    ? ProcessingResult.pass(payload)
                    : ProcessingResult.reject(describe(errors), issues(errors));
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

    private static Schema compile(String jsonSchema) {
        JsonNode node;
        try {
            node = MAPPER.readTree(jsonSchema);
        } catch (Exception e) {
            throw new IllegalArgumentException("jsonSchema is not valid JSON: " + e.getMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("jsonSchema must be a JSON object");
        }
        try {
            Schema compiled = REGISTRY.getSchema(node);
            // Lazy by default in 2.x: preload now so a broken $ref fails here, not mid-task.
            compiled.initializeValidators();
            return compiled;
        } catch (SchemaException e) {
            throw new IllegalArgumentException("jsonSchema is not a valid JSON Schema: " + e.getMessage(), e);
        }
    }

    /**
     * Every violation as data, for a caller that does not want to parse {@link #describe}'s
     * sentence. Capped at {@value #MAX_ISSUES} so a payload with thousands of bad array
     * elements cannot make the rejection itself the largest object in the response.
     */
    private static List<ProcessingResult.Issue> issues(List<com.networknt.schema.Error> errors) {
        return errors.stream()
                .limit(MAX_ISSUES)
                .map(e -> new ProcessingResult.Issue(e.getInstanceLocation().toString(), e.getMessage()))
                .toList();
    }

    private static String describe(List<com.networknt.schema.Error> errors) {
        StringBuilder reason = new StringBuilder("Schema violations (").append(errors.size()).append("): ");
        int shown = Math.min(errors.size(), MAX_REPORTED_ERRORS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) reason.append("; ");
            reason.append(errors.get(i));   // "<instance path>: <message>"
        }
        if (errors.size() > shown) {
            reason.append("; … and ").append(errors.size() - shown).append(" more");
        }
        return reason.toString();
    }
}
