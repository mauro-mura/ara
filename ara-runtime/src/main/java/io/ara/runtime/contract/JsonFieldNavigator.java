package io.ara.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * Shared dot-path navigation over a JSON document (e.g. {@code "result.status"}).
 *
 * <p>Used by both {@link JsonFieldExtractor} and {@link JsonFieldValueValidator} for the
 * same walk: split on {@code '.'}, descend segment by segment, and treat a null, absent
 * or missing final value as "field not found". One tiny package-private helper instead of
 * two copies — exactly the abstraction A3 sanctions once a second real consumer exists.
 */
final class JsonFieldNavigator {

    /**
     * Dot-path separator, precompiled: {@code "\\."} has no fast path in {@link String#split}
     * (a two-char pattern is compiled), so navigating a field path recompiled it every call.
     */
    private static final Pattern FIELD_SEPARATOR = Pattern.compile("\\.");

    private JsonFieldNavigator() {}

    /** Returns the node at {@code path}, or {@code null} if a segment or the final value is missing/null. */
    static JsonNode navigate(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : FIELD_SEPARATOR.split(path)) {
            if (node == null || node.isMissingNode()) break;
            node = node.get(part);
        }
        return (node != null && !node.isNull() && !node.isMissingNode()) ? node : null;
    }
}