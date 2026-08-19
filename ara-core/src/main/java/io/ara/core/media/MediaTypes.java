package io.ara.core.media;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The MIME vocabulary ARA accepts for task media, and the coarse category each of those
 * MIME types belongs to.
 *
 * <h2>Why the allowlist and the classification live in the same type</h2>
 * <p>Both are pure functions of the same {@code mimeType} string. Split across two types —
 * one holding "which MIMEs exist", another answering "what kind is this one" — they become
 * two independent enumerations of the same vocabulary, free to drift the moment one grows
 * an entry the other doesn't: adding {@code image/gif} to the allowlist while the
 * classifier still has no branch for it produces a media reference that constructs fine and
 * then fails, or silently mis-classifies, at the adapter. Here they are a single
 * {@link Map}: its key set <em>is</em> the allowlist, its value <em>is</em> the kind, so a
 * new MIME cannot be added without also declaring its category. {@link MediaKind} is nested
 * for the same reason — it is not meaningful apart from this table.
 *
 * <h2>Why the list is short</h2>
 * <p>It covers what the providers ARA speaks to actually accept today, and nothing else.
 * Audio and video are deliberately absent: langchain4j models them, but no ARA use case
 * needs them yet and an entry here is a contract every adapter has to honour forever.
 * Grow the table when a real case arrives, not in anticipation of one.
 *
 * <h2>Where validation happens</h2>
 * <p>{@link #normalized(String)} is called from {@link MediaRef}'s constructor, so an
 * unsupported MIME fails where the reference is built — next to the caller that supplied
 * it. The alternative, filtering unknown types out somewhere down the pipeline, turns a
 * typo into a document that silently never reaches the model.
 */
public final class MediaTypes {

    /**
     * The whole vocabulary: allowlist and classification in one table.
     *
     * <p>Kept as MIME → kind (rather than a set plus a switch) precisely so the two cannot
     * disagree — see the class javadoc.
     */
    private static final Map<String, MediaKind> VOCABULARY = Map.of(
            "image/png",       MediaKind.IMAGE,
            "image/jpeg",      MediaKind.IMAGE,
            "image/webp",      MediaKind.IMAGE,
            "application/pdf", MediaKind.DOCUMENT,
            "text/plain",      MediaKind.TEXT,
            "text/markdown",   MediaKind.TEXT,
            "text/csv",        MediaKind.TEXT
    );

    private MediaTypes() {}

    /**
     * The coarse category of a media type. Adapters branch on this — a provider that takes
     * images but not documents needs to tell them apart — while the rest of ARA carries the
     * MIME string and never looks at the category at all.
     */
    public enum MediaKind {
        /** A raster image the model looks at directly. */
        IMAGE,
        /** A binary document (today: PDF) the model reads natively, layout included. */
        DOCUMENT,
        /** Text-bearing content that can be decoded and inlined as plain text. */
        TEXT
    }

    /** The accepted MIME types, lowercase and without parameters. Immutable. */
    public static Set<String> allowed() {
        return VOCABULARY.keySet();
    }

    /**
     * Returns {@code mimeType} in canonical form (trimmed, lowercase), or throws if it is
     * not in {@link #allowed()}.
     *
     * <p>MIME parameters ({@code text/plain; charset=utf-8}) are <em>not</em> stripped: they
     * make the value fail the allowlist. Accepting them would mean deciding which parameters
     * are meaningful — a question no caller has asked yet, and one that ends with two values
     * that mean the same thing hashing differently.
     *
     * @throws IllegalArgumentException if {@code mimeType} is not an accepted type
     */
    public static String normalized(String mimeType) {
        String canonical = canonicalize(mimeType);
        if (!VOCABULARY.containsKey(canonical)) {
            throw new IllegalArgumentException(
                    "Unsupported media type '" + mimeType + "' — accepted types are " + allowed());
        }
        return canonical;
    }

    /**
     * Returns the category of an accepted MIME type.
     *
     * @throws IllegalArgumentException if {@code mimeType} is not an accepted type
     */
    public static MediaKind kindOf(String mimeType) {
        return VOCABULARY.get(normalized(mimeType));
    }

    private static String canonicalize(String mimeType) {
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        return mimeType.trim().toLowerCase(Locale.ROOT);
    }
}
