package io.ara.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Validates the value of a single JSON field against an allowed set of strings or a
 * numeric range.
 *
 * <p>The field is addressed with the same dot-path notation as {@link JsonFieldExtractor}
 * (e.g. {@code "result.status"}). Use {@link #oneOf(String, String...)} to constrain a
 * field to an enumerated set of string values, or {@link #inRange(String, double, double)}
 * to constrain a numeric field to a closed interval. Can be used as both an
 * {@link InputProcessor} and an {@link OutputProcessor}.
 *
 * <pre>{@code
 * JsonFieldValueValidator.oneOf("status", "APPROVED", "REJECTED")
 * JsonFieldValueValidator.inRange("score", 0.0, 1.0)
 * }</pre>
 */
public final class JsonFieldValueValidator implements InputProcessor, OutputProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private enum Mode { ONE_OF, RANGE }

    private final String      fieldPath;
    private final Mode        mode;
    private final Set<String> allowedValues; // ONE_OF only
    private final double      min;           // RANGE only
    private final double      max;           // RANGE only

    private JsonFieldValueValidator(String fieldPath, Mode mode,
                                     Set<String> allowedValues, double min, double max) {
        this.fieldPath     = fieldPath;
        this.mode          = mode;
        this.allowedValues = allowedValues;
        this.min           = min;
        this.max           = max;
    }

    /** Rejects the payload unless the field's string value is one of {@code allowedValues}. */
    public static JsonFieldValueValidator oneOf(String fieldPath, String... allowedValues) {
        Objects.requireNonNull(fieldPath, "fieldPath must not be null");
        if (allowedValues == null || allowedValues.length == 0) {
            throw new IllegalArgumentException("allowedValues must not be empty");
        }
        return new JsonFieldValueValidator(fieldPath, Mode.ONE_OF,
                new LinkedHashSet<>(Arrays.asList(allowedValues)), 0, 0);
    }

    /** Rejects the payload unless the field's numeric value lies within {@code [min, max]}. */
    public static JsonFieldValueValidator inRange(String fieldPath, double min, double max) {
        Objects.requireNonNull(fieldPath, "fieldPath must not be null");
        if (min > max) {
            throw new IllegalArgumentException("min (%s) must be <= max (%s)".formatted(min, max));
        }
        return new JsonFieldValueValidator(fieldPath, Mode.RANGE, null, min, max);
    }

    @Override
    public ProcessingResult process(String payload) {
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return ProcessingResult.reject("Payload is not valid JSON: " + e.getMessage());
        }

        JsonNode node = root;
        for (String part : fieldPath.split("\\.")) {
            if (node == null || node.isMissingNode()) break;
            node = node.get(part);
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return ProcessingResult.reject("Field '" + fieldPath + "' not found in payload");
        }

        return switch (mode) {
            case ONE_OF -> checkOneOf(node, payload);
            case RANGE  -> checkRange(node, payload);
        };
    }

    private ProcessingResult checkOneOf(JsonNode node, String payload) {
        String value = node.asText();
        if (!allowedValues.contains(value)) {
            return ProcessingResult.reject(
                    "Field '%s' value '%s' not in allowed set %s"
                            .formatted(fieldPath, value, allowedValues));
        }
        return ProcessingResult.pass(payload);
    }

    private ProcessingResult checkRange(JsonNode node, String payload) {
        if (!node.isNumber()) {
            return ProcessingResult.reject(
                    "Field '%s' is not numeric (value: %s)".formatted(fieldPath, node.asText()));
        }
        double value = node.asDouble();
        if (value < min || value > max) {
            return ProcessingResult.reject(
                    "Field '%s' value %s out of range [%s, %s]".formatted(fieldPath, value, min, max));
        }
        return ProcessingResult.pass(payload);
    }
}
