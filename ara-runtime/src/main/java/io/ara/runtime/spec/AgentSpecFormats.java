package io.ara.runtime.spec;

import io.ara.core.spec.AgentSpec;
import io.ara.core.spec.AgentSpecFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The set of {@link AgentSpecFormat}s known to a caller, and the file-level entry points that
 * use them: read a spec from a file, write one to a file, picking the format by extension.
 *
 * <p>The choice of format by id or extension, and the refusal of ambiguous registrations, is
 * {@link DocumentFormats}' job; this class is the agent-typed view of it and adds the
 * {@link AgentSpecDocument} mapping on top. Formats are registered by name, explicitly: there
 * is no {@code ServiceLoader}, and the cost of {@code defaults().with(new YamlFormat())} is
 * one line.
 *
 * <p>Immutable: {@link #with} returns a new registry. Thread-safe.
 */
public final class AgentSpecFormats {

    // Only with(AgentSpecFormat) adds to it, so every format in here is an AgentSpecFormat and
    // the casts below cannot fail.
    private final DocumentFormats formats;

    private AgentSpecFormats(DocumentFormats formats) {
        this.formats = formats;
    }

    /** A registry holding the built-in JSON format. */
    public static AgentSpecFormats defaults() {
        return new AgentSpecFormats(DocumentFormats.empty()).with(new JsonAgentSpecFormat());
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
        return new AgentSpecFormats(formats.with(format));
    }

    /** @throws IllegalArgumentException listing the registered ids if none matches */
    public AgentSpecFormat byId(String id) {
        return (AgentSpecFormat) formats.byId(id);
    }

    /** @throws IllegalArgumentException listing the registered extensions if none matches */
    public AgentSpecFormat forFileName(String fileName) {
        return (AgentSpecFormat) formats.forFileName(fileName);
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
}
