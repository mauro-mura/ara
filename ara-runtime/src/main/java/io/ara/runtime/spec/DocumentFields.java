package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A typed, path-aware reader over one JSON object of an agent document.
 *
 * <p><b>Why a hand-written reader.</b> The decoder must not use reflection, annotations or
 * {@code ObjectMapper.convertValue} (the repository keeps its own code free of them), and it
 * must say <em>which field</em> is wrong. So every access goes through this class, which
 * knows its dotted path and turns a mistake into an {@link AgentSpecDocumentException}.
 *
 * <p><b>Strict types, no coercion.</b> {@code "5"} is not an integer and {@code 1} is not
 * {@code true}. The same document is read from different formats (YAML turns {@code no} into a
 * boolean and {@code 3.10} into a decimal); without strictness one file could mean two
 * things. A JSON {@code null} counts as "absent", so a value left out and a value nulled
 * behave alike.
 *
 * <p><b>Unknown fields are errors.</b> Every accessor records the field it was asked for;
 * {@link #finish()} rejects any key nobody asked about, so a typo
 * ({@code maxIteration}) fails instead of silently becoming a default.
 *
 * <p>Checklist: a field added to the document must be read in the decoder <em>and</em>
 * written in the matching encoder of the same section; the round-trip test fails otherwise.
 *
 * <p>Not thread-safe; confined to one decode call.
 */
final class DocumentFields {

    private final String path;
    private final JsonNode node;
    private final Set<String> asked = new LinkedHashSet<>();

    DocumentFields(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new AgentSpecDocumentException(path, "expected an object, got " + describe(node));
        }
        this.node = node;
        this.path = path;
    }

    /** An empty object at {@code path}: what an optional section that is absent decodes as. */
    static DocumentFields empty(String path) {
        return new DocumentFields(JsonNodeFactory.instance.objectNode(), path);
    }

    // ── reading ────────────────────────────────────────────────────────────────────────

    String string(String field) {
        JsonNode value = take(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw typeError(field, "a string", value);
        }
        return value.asText();
    }

    String requireString(String field) {
        String value = string(field);
        if (value == null) {
            throw new AgentSpecDocumentException(at(field), "is required");
        }
        return value;
    }

    Integer integer(String field) {
        JsonNode value = take(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw typeError(field, "an integer", value);
        }
        return value.intValue();
    }

    Long longValue(String field) {
        JsonNode value = take(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw typeError(field, "an integer", value);
        }
        return value.longValue();
    }

    Double decimal(String field) {
        JsonNode value = take(field);
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            throw typeError(field, "a number", value);
        }
        return value.doubleValue();
    }

    Boolean bool(String field) {
        JsonNode value = take(field);
        if (value == null) {
            return null;
        }
        if (!value.isBoolean()) {
            throw typeError(field, "true or false", value);
        }
        return value.booleanValue();
    }

    Duration duration(String field) {
        String text = string(field);
        if (text == null) {
            return null;
        }
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException e) {
            throw new AgentSpecDocumentException(at(field),
                    "expected an ISO-8601 duration such as PT5M, got '" + text + "'", e);
        }
    }

    <E extends Enum<E>> E enumValue(String field, Class<E> type) {
        String text = string(field);
        if (text == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new AgentSpecDocumentException(at(field),
                    "expected one of " + Arrays.toString(type.getEnumConstants()) + ", got '" + text + "'", e);
        }
    }

    /** The list at {@code field}, {@code null} if absent; every element must be a string. */
    List<String> stringList(String field) {
        JsonNode value = take(field);
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            throw typeError(field, "an array of strings", value);
        }
        List<String> out = new ArrayList<>(value.size());
        for (int index = 0; index < value.size(); index++) {
            JsonNode element = value.get(index);
            if (!element.isTextual()) {
                throw new AgentSpecDocumentException(at(field) + "[" + index + "]",
                        "expected a string, got " + describe(element));
            }
            out.add(element.asText());
        }
        return out;
    }

    /** The object at {@code field}, or an empty object if absent (so its defaults apply). */
    DocumentFields objectOrEmpty(String field) {
        JsonNode value = take(field);
        return value == null ? empty(at(field)) : new DocumentFields(value, at(field));
    }

    DocumentFields requireObject(String field) {
        JsonNode value = take(field);
        if (value == null) {
            throw new AgentSpecDocumentException(at(field), "is required");
        }
        return new DocumentFields(value, at(field));
    }

    /** The object at {@code field}, or {@code null} if absent. */
    DocumentFields optionalObject(String field) {
        JsonNode value = take(field);
        return value == null ? null : new DocumentFields(value, at(field));
    }

    /** Every element of the array at {@code field}, each as an object; empty if absent. */
    List<DocumentFields> objectList(String field) {
        JsonNode value = take(field);
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw typeError(field, "an array of objects", value);
        }
        List<DocumentFields> out = new ArrayList<>(value.size());
        for (int index = 0; index < value.size(); index++) {
            out.add(new DocumentFields(value.get(index), at(field) + "[" + index + "]"));
        }
        return out;
    }

    /** The raw node at {@code field} for a value with more than one legal shape; {@code null} if absent. */
    JsonNode raw(String field) {
        return take(field);
    }

    /** Rejects any key of this object that no accessor asked about. */
    void finish() {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!asked.contains(name)) {
                throw new AgentSpecDocumentException(at(name), "unknown field; known fields here: " + asked);
            }
        }
    }

    // ── helpers shared by the sections ─────────────────────────────────────────────────

    /** Applies {@code setter} only when the document supplied a value, so builder defaults stay the single source. */
    static <T> void set(Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Runs a domain constructor or builder, turning its {@link IllegalArgumentException} (the
     * constructors validate their own invariants) into one that carries {@code path}.
     */
    static <T> T guard(String path, Supplier<T> construction) {
        try {
            return construction.get();
        } catch (AgentSpecDocumentException alreadyLocated) {
            throw alreadyLocated;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AgentSpecDocumentException(path, e.getMessage(), e);
        }
    }

    String path() {
        return path;
    }

    String at(String field) {
        return path.isEmpty() ? field : path + "." + field;
    }

    // ── writing helpers ────────────────────────────────────────────────────────────────

    static void put(ObjectNode out, String field, String value) {
        if (value != null) {
            out.put(field, value);
        }
    }

    static ArrayNode stringArray(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }

    // ── internals ──────────────────────────────────────────────────────────────────────

    private JsonNode take(String field) {
        asked.add(field);
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private AgentSpecDocumentException typeError(String field, String expected, JsonNode value) {
        return new AgentSpecDocumentException(at(field), "expected " + expected + ", got " + describe(value));
    }

    private static String describe(JsonNode value) {
        return value == null ? "nothing" : value.getNodeType().name().toLowerCase();
    }
}
