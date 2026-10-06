package io.ara.runtime.spec;

import io.ara.core.spec.AgentSpec;
import io.ara.core.spec.AgentSpecFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The set of {@link AgentSpecFormat}s known to a caller, and the file-level entry points that
 * use them: read a spec from a file, write one to a file, picking the format by extension.
 *
 * <p>Formats are registered by name, explicitly, like every other pluggable thing in the
 * runtime. There is no {@code ServiceLoader}: a format that appears because a jar happens to
 * be on the classpath is the kind of magic this code base avoids, and the cost of
 * {@code defaults().with(new YamlFormat())} is one line.
 *
 * <p>Immutable: {@link #with} returns a new registry. Thread-safe.
 */
public final class AgentSpecFormats {

    private final Map<String, AgentSpecFormat> byId;
    private final Map<String, AgentSpecFormat> byExtension;

    private AgentSpecFormats(Map<String, AgentSpecFormat> byId, Map<String, AgentSpecFormat> byExtension) {
        this.byId = byId;
        this.byExtension = byExtension;
    }

    /** A registry holding the built-in JSON format. */
    public static AgentSpecFormats defaults() {
        return new AgentSpecFormats(Map.of(), Map.of()).with(new JsonAgentSpecFormat());
    }

    /**
     * A registry that also knows {@code format}.
     *
     * @throws IllegalArgumentException if its id or one of its extensions is already taken: two
     *         formats answering to {@code ".yaml"} would make a file's meaning depend on
     *         registration order
     */
    public AgentSpecFormats with(AgentSpecFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        Map<String, AgentSpecFormat> ids = new LinkedHashMap<>(byId);
        Map<String, AgentSpecFormat> extensions = new LinkedHashMap<>(byExtension);
        if (ids.putIfAbsent(format.id(), format) != null) {
            throw new IllegalArgumentException("a format with id '" + format.id() + "' is already registered");
        }
        for (String extension : format.fileExtensions()) {
            if (extensions.putIfAbsent(extension.toLowerCase(Locale.ROOT), format) != null) {
                throw new IllegalArgumentException("extension '" + extension + "' is already registered");
            }
        }
        return new AgentSpecFormats(Map.copyOf(ids), Map.copyOf(extensions));
    }

    /** @throws IllegalArgumentException listing the registered ids if none matches */
    public AgentSpecFormat byId(String id) {
        AgentSpecFormat format = byId.get(id);
        if (format == null) {
            throw new IllegalArgumentException("no format with id '" + id + "'; registered: " + sorted(byId));
        }
        return format;
    }

    /** @throws IllegalArgumentException listing the registered extensions if none matches */
    public AgentSpecFormat forFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
        AgentSpecFormat format = byExtension.get(extension);
        if (format == null) {
            throw new IllegalArgumentException("no format for '" + fileName + "'; registered extensions: "
                    + sorted(byExtension));
        }
        return format;
    }

    /**
     * Reads the file at {@code path} as a fresh root spec, in the format its extension names.
     *
     * @throws AgentSpecDocumentException if the document is invalid
     * @throws IOException if the file cannot be read or is not valid in its format
     */
    public AgentSpec read(Path path) throws IOException {
        AgentSpecFormat format = forFileName(path.getFileName().toString());
        try (InputStream in = Files.newInputStream(path)) {
            return AgentSpecDocument.decode(format.read(in));
        }
    }

    /** Writes {@code spec} to {@code path} in the format its extension names, replacing the file. */
    public void write(AgentSpec spec, Path path) throws IOException {
        AgentSpecFormat format = forFileName(path.getFileName().toString());
        try (OutputStream out = Files.newOutputStream(path)) {
            format.write(AgentSpecDocument.encode(spec), out);
        }
    }

    /** Reads a spec from {@code in}, which the caller keeps ownership of, in the format {@code formatId}. */
    public AgentSpec read(String formatId, InputStream in) throws IOException {
        return AgentSpecDocument.decode(byId(formatId).read(in));
    }

    /** Writes {@code spec} to {@code out}, which the caller keeps ownership of, in the format {@code formatId}. */
    public void write(String formatId, AgentSpec spec, OutputStream out) throws IOException {
        byId(formatId).write(AgentSpecDocument.encode(spec), out);
    }

    private static List<String> sorted(Map<String, AgentSpecFormat> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        return keys;
    }
}
