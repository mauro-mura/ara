package io.ara.core.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * A way of carrying an agent definition as bytes: it turns bytes into the neutral
 * {@link JsonNode} tree of an agent document, and back. It knows nothing about agents.
 *
 * <p><b>Why a tree and not a string.</b> The mapping between that tree and an
 * {@link AgentSpec} (field names, types, defaults, validation) is written once, in the
 * runtime's {@code AgentSpecDocument}. A format only has to be a faithful translator of a
 * tree, so adding one (YAML, a binary encoding, a proprietary file) never means a second
 * copy of the agent mapping that could drift from the first. The alternative — one
 * {@code AgentSpec} codec per format — was rejected for exactly that drift.
 *
 * <p><b>Why streams and not readers/writers.</b> A binary format cannot be expressed over
 * characters. Each format decides its own charset; the text formats use UTF-8. This
 * signature is public API published to Maven Central, so it is cheaper to get right now than
 * to widen later.
 *
 * <p>Two things a format deliberately does <em>not</em> do: validate the document (the
 * decoder does, with field paths in its errors) or choose a schema version (the document
 * carries its own {@code schemaVersion} field).
 *
 * <p>Implementations must be thread-safe: one instance is shared by every caller of a
 * format registry.
 */
public interface AgentSpecFormat {

    /** A short, stable, lower-case name — {@code "json"} — used to pick a format explicitly. */
    String id();

    /**
     * File extensions this format owns, lower-case and with the leading dot ({@code ".json"}),
     * used to pick a format from a file name.
     */
    Set<String> fileExtensions();

    /**
     * Parses {@code in} into a document tree. Does not close the stream.
     *
     * @throws IOException if the bytes are not valid in this format
     */
    JsonNode read(InputStream in) throws IOException;

    /**
     * Writes {@code tree} to {@code out}, flushing but not closing it. The same tree must
     * always produce the same bytes, so two exports of one agent are identical and a version
     * control diff shows only what changed.
     */
    void write(JsonNode tree, OutputStream out) throws IOException;
}
