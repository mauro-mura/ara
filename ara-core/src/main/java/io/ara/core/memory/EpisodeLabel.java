package io.ara.core.memory;

import java.util.Objects;

/**
 * A plain sub-type label for an episodic {@link MemoryEntry} — e.g. {@code
 * "task_completed"}, {@code "reflexion"}. The other shape {@code MemoryEntry.metadata()}
 * may carry, distinct from a {@link ToolCallMetadata} tool-call correlation.
 */
public record EpisodeLabel(String value) implements EntryMetadata {
    public EpisodeLabel {
        Objects.requireNonNull(value, "value must not be null");
    }
}
