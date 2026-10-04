package io.ara.runtime.artifact;

import io.ara.core.artifact.ArtifactExtractor;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns the final answer of an agent into stored artifacts: extracts its parts and puts each in the
 * runtime's {@link MediaStore}, returning the references the response will carry.
 *
 * <h2>When it does nothing</h2>
 * <p>A runtime without an {@link ArtifactExtractor}, or whose store is {@link MediaStore#noop()}, gets
 * {@link #none()}: {@link #emit} returns an empty list without touching anything. That is the
 * default, and it keeps the behaviour of a deployment that never asked for artifacts identical to
 * what it was before they existed.
 *
 * <h2>Why a failure here never fails the agent</h2>
 * <p>The artifacts are derived from an answer the agent already completed. Losing them is a degraded
 * response, not a failed task: the text is intact and a consumer can still read it. So an extractor
 * that throws, or a store that refuses a part, is logged at {@code warn} and the response has no
 * artifacts — all or none, never half of them, so a consumer never mistakes a partial list for the
 * whole answer. (A part already stored stays in the store: it is content-addressed and costs one
 * entry, and deleting it would be a second way to fail.)
 *
 * <p>Thread-safe: immutable, and the extractor and the store it holds are required to be.
 */
public final class ArtifactSink {

    private static final Logger log = LoggerFactory.getLogger(ArtifactSink.class);

    private static final ArtifactSink NONE = new ArtifactSink(ArtifactExtractor.none(), MediaStore.noop(), false);

    private final ArtifactExtractor extractor;
    private final MediaStore store;
    private final boolean active;

    private ArtifactSink(ArtifactExtractor extractor, MediaStore store, boolean active) {
        this.extractor = extractor;
        this.store = store;
        this.active = active;
    }

    /** The sink that emits nothing. */
    public static ArtifactSink none() {
        return NONE;
    }

    /**
     * A sink for {@code extractor} and {@code store}; {@link #none()} when either is the shared
     * default, since the pair then cannot produce a stored artifact.
     */
    public static ArtifactSink of(ArtifactExtractor extractor, MediaStore store) {
        Objects.requireNonNull(extractor, "extractor must not be null");
        Objects.requireNonNull(store, "store must not be null");
        if (extractor == ArtifactExtractor.none() || store == MediaStore.noop()) {
            return NONE;
        }
        return new ArtifactSink(extractor, store, true);
    }

    /**
     * The references to the artifacts of {@code content}, in the order they appear, each already
     * stored; empty when there are none, when this sink is {@link #none()}, or when extraction or
     * storage failed (see the class comment).
     */
    public List<MediaRef> emit(String content) {
        if (!active || content == null || content.isEmpty()) {
            return List.of();
        }
        try {
            List<MediaRef> references = new ArrayList<>();
            for (ArtifactExtractor.Extracted part : extractor.extract(content)) {
                references.add(store.put(part.name(), part.mimeType(), part.bytes()));
            }
            return List.copyOf(references);
        } catch (RuntimeException failure) {
            log.warn("Artifact extraction failed; the response keeps its text and has no artifacts", failure);
            return List.of();
        }
    }
}
