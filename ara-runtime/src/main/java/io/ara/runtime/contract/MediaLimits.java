package io.ara.runtime.contract;

import io.ara.core.agent.processor.MediaValidator;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaTypes;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Per-agent ceiling on how much a task may attach: how many files, how many bytes in total,
 * and — optionally — a narrower set of accepted types than the global one.
 *
 * <h2>Quantitative only, and why that is the whole job</h2>
 * <p>Whether a MIME type is acceptable <em>at all</em> is already settled: {@code MediaRef}
 * validates against {@code MediaTypes.allowed()} in its constructor, so an unsupported type
 * never becomes a reference in the first place. Re-checking it here would be a second copy of
 * that decision, free to disagree with the first. What is left is the part that genuinely
 * varies per agent: <em>how much</em>. A summarisation agent might accept one document;
 * an ingestion agent twenty.
 *
 * <p>The optional {@code acceptedTypes} narrows the global vocabulary, it never widens it —
 * an agent can refuse PDFs, but no agent can accept a type ARA does not support, because such
 * a reference cannot exist. The constructor rejects a type outside the global set rather than
 * accepting a limit that could never match anything.
 *
 * <h2>What this does not defend against</h2>
 * <p>A cap on count and size limits the <em>volume</em> of hostile content, not its nature.
 * Text printed inside a PDF or rendered into an image never passes through
 * {@link InputSanitizer}, which only ever sees the task's input string. A frame in the prompt
 * (added where media is flattened, in the adapter) and a validated output schema are the
 * mitigations that exist today; neither is a complete answer, and a per-agent content policy
 * would be a design decision of its own rather than something to slip in here.
 *
 * <p>External ({@code uri}-backed) references whose size is unknown count toward the file
 * count but contribute nothing to the byte total — ARA has not seen those bytes and will not
 * fetch them to weigh them. That is stated rather than hidden: a byte cap cannot bound what
 * it was never told.
 */
public final class MediaLimits implements MediaValidator {

    private final int         maxFiles;
    private final long        maxTotalBytes;
    private final Set<String> acceptedTypes;

    private MediaLimits(int maxFiles, long maxTotalBytes, Set<String> acceptedTypes) {
        if (maxFiles < 0)      throw new IllegalArgumentException("maxFiles must be >= 0");
        if (maxTotalBytes < 0) throw new IllegalArgumentException("maxTotalBytes must be >= 0");
        for (String type : acceptedTypes) {
            // Fails here rather than silently rejecting every task: an agent configured to
            // accept a type ARA does not know is a configuration mistake, not a strict policy.
            MediaTypes.normalized(type);
        }
        this.maxFiles      = maxFiles;
        this.maxTotalBytes = maxTotalBytes;
        this.acceptedTypes = Set.copyOf(acceptedTypes);
    }

    /** At most {@code maxFiles} attachments totalling at most {@code maxTotalBytes}, any supported type. */
    public static MediaLimits of(int maxFiles, long maxTotalBytes) {
        return new MediaLimits(maxFiles, maxTotalBytes, MediaTypes.allowed());
    }

    /**
     * As {@link #of(int, long)}, but restricted to {@code acceptedTypes} — a subset of
     * {@code MediaTypes.allowed()}, for an agent that should see only some of them.
     *
     * @throws IllegalArgumentException if a type is not in the global allowlist
     */
    public static MediaLimits of(int maxFiles, long maxTotalBytes, Set<String> acceptedTypes) {
        return new MediaLimits(maxFiles, maxTotalBytes, acceptedTypes);
    }

    /** Rejects any attachment at all — for an agent that must stay text-only. */
    public static MediaLimits none() {
        return new MediaLimits(0, 0, Set.of());
    }

    @Override
    public Optional<String> validate(List<MediaRef> media) {
        if (media.isEmpty()) return Optional.empty();

        if (media.size() > maxFiles) {
            return Optional.of("too many attachments: %d, at most %d allowed"
                    .formatted(media.size(), maxFiles));
        }

        long total = 0;
        for (MediaRef ref : media) {
            if (!acceptedTypes.contains(ref.mimeType())) {
                return Optional.of("attachment '%s' has type %s, which this agent does not accept (accepts: %s)"
                        .formatted(ref.name(), ref.mimeType(), acceptedTypes.isEmpty() ? "no attachments" : acceptedTypes));
            }
            total += ref.sizeBytes();
        }

        if (total > maxTotalBytes) {
            return Optional.of("attachments total %d bytes, at most %d allowed"
                    .formatted(total, maxTotalBytes));
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MediaLimits[maxFiles=%d, maxTotalBytes=%d, acceptedTypes=%s]",
                maxFiles, maxTotalBytes, acceptedTypes);
    }
}
