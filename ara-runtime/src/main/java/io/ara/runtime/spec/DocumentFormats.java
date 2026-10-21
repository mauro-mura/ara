package io.ara.runtime.spec;

import io.ara.core.spec.DocumentFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The set of {@link DocumentFormat}s known to a caller, and the choice among them by id or by
 * file extension. It is the one place that decides which format a name or a file means; the
 * agent registry ({@link AgentSpecFormats}) and the schedule codec ({@link AgentScheduleDocument})
 * both go through it.
 *
 * <p>Formats are registered by name, explicitly, like every other pluggable thing in the
 * runtime. There is no {@code ServiceLoader}: a format that appears because a jar happens to
 * be on the classpath is the kind of magic this code base avoids, and the cost of
 * {@code defaults().with(new YamlFormat())} is one line.
 *
 * <p>Immutable: {@link #with} returns a new registry. Thread-safe.
 */
public final class DocumentFormats {

    private final Map<String, DocumentFormat> byId;
    private final Map<String, DocumentFormat> byExtension;

    private DocumentFormats(Map<String, DocumentFormat> byId, Map<String, DocumentFormat> byExtension) {
        this.byId = byId;
        this.byExtension = byExtension;
    }

    /** A registry holding the built-in JSON format. */
    public static DocumentFormats defaults() {
        return empty().with(new JsonAgentSpecFormat());
    }

    static DocumentFormats empty() {
        return new DocumentFormats(Map.of(), Map.of());
    }

    /**
     * A registry that also knows {@code format}.
     *
     * @throws IllegalArgumentException if its id or one of its extensions is already taken: two
     *         formats answering to {@code ".yaml"} would make a file's meaning depend on
     *         registration order
     */
    public DocumentFormats with(DocumentFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        Map<String, DocumentFormat> ids = new LinkedHashMap<>(byId);
        Map<String, DocumentFormat> extensions = new LinkedHashMap<>(byExtension);
        if (ids.putIfAbsent(format.id(), format) != null) {
            throw new IllegalArgumentException("a format with id '" + format.id() + "' is already registered");
        }
        for (String extension : format.fileExtensions()) {
            if (extensions.putIfAbsent(extension.toLowerCase(Locale.ROOT), format) != null) {
                throw new IllegalArgumentException("extension '" + extension + "' is already registered");
            }
        }
        return new DocumentFormats(Map.copyOf(ids), Map.copyOf(extensions));
    }

    /** @throws IllegalArgumentException listing the registered ids if none matches */
    public DocumentFormat byId(String id) {
        DocumentFormat format = byId.get(id);
        if (format == null) {
            throw new IllegalArgumentException("no format with id '" + id + "'; registered: " + sorted(byId));
        }
        return format;
    }

    /** @throws IllegalArgumentException listing the registered extensions if none matches */
    public DocumentFormat forFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
        DocumentFormat format = byExtension.get(extension);
        if (format == null) {
            throw new IllegalArgumentException("no format for '" + fileName + "'; registered extensions: "
                    + sorted(byExtension));
        }
        return format;
    }

    private static List<String> sorted(Map<String, DocumentFormat> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        return keys;
    }
}
