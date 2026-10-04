package io.ara.core.artifact;

import java.util.List;
import java.util.Objects;

/**
 * Decides which parts of an agent's final answer are artifacts: pieces of output — a block of code,
 * a document — that a consumer wants to read, check or hand to another agent without re-parsing the
 * answer text.
 *
 * <h2>Why a separate step, and why not the model</h2>
 * <p>The model is never asked to produce an artifact. It would have to put a long text in the
 * arguments of a tool call, which are JSON, and the escaping of that text would move there — the
 * very problem an artifact exists to avoid. So the system derives the parts from the answer the
 * model already wrote, and this interface is where that derivation is chosen.
 *
 * <h2>Why the runtime chooses, not the agent</h2>
 * <p>Like the {@code MediaStore}, <em>how</em> parts are extracted is an infrastructure decision, not
 * a property of one agent: an agent answering differently from the agent it delegates to would be a
 * surprise for whoever reads both. The runtime therefore holds one extractor for all its agents.
 *
 * <h2>Why an interface and no more than that</h2>
 * <p>This is a public extension point: the runtime ships one extractor for fenced code blocks, and a
 * deployment whose answers have another structure (a table, a patch) supplies its own. The result
 * carries bytes and a name, not a {@code MediaRef}: storing them is the runtime's job, so an
 * extractor stays a pure function of the text and needs no store to be tested.
 *
 * <p>Implementations must be thread-safe: one instance serves every concurrent task of the runtime.
 * They are expected to be pure functions of {@code content}.
 */
@FunctionalInterface
public interface ArtifactExtractor {

    /**
     * One extracted part, before it is stored.
     *
     * @param name     the name a human would recognise, with an extension when the part has a
     *                 language ({@code block-1.py}); never blank
     * @param mimeType the media type of {@code bytes}; must be accepted by the {@code MediaStore}
     *                 it will be stored in (see {@code MediaTypes#allowed()})
     * @param bytes    the content of the part; never empty
     */
    record Extracted(String name, String mimeType, byte[] bytes) {

        public Extracted {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(mimeType, "mimeType must not be null");
            Objects.requireNonNull(bytes, "bytes must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("an artifact needs a name");
            }
            if (bytes.length == 0) {
                throw new IllegalArgumentException("an empty artifact is not an artifact: " + name);
            }
            // The array is copied so a record that is shared between threads cannot be changed under a reader.
            bytes = bytes.clone();
        }

        /** A copy, never the stored array, for the same reason the constructor copies it. */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * The artifacts of {@code content}, in the order they appear; empty when it has none.
     *
     * @param content the final answer text of an agent that completed its task
     */
    List<Extracted> extract(String content);

    /**
     * The extractor that finds nothing — the default, so a runtime that never asks for artifacts has
     * none. A single shared instance, not a fresh lambda per call, so that "is this still the default?"
     * is answerable by identity, the same way {@code MediaStore.noop()} is.
     */
    static ArtifactExtractor none() {
        return NoArtifacts.INSTANCE;
    }

    /** Holder for the shared instance: an interface cannot keep a private constant of its own. */
    final class NoArtifacts {
        private static final ArtifactExtractor INSTANCE = content -> List.of();

        private NoArtifacts() {
        }
    }
}
