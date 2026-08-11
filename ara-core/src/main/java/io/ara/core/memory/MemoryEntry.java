package io.ara.core.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * A single entry in any of the ARA memory tiers.
 *
 * @param role      speaker role: "system", "user", "assistant", "tool", "episode"
 * @param content   the text content of the entry
 * @param timestamp when this entry was recorded
 * @param metadata  optional label (e.g. tool name, episode type)
 */
public record MemoryEntry(
        String role,
        String content,
        Instant timestamp,
        String metadata
) {
    public MemoryEntry {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /** Creates a memory entry with the current timestamp and no metadata. */
    public static MemoryEntry of(String role, String content) {
        return new MemoryEntry(role, content, Instant.now(), null);
    }

    /** Creates a memory entry with the current timestamp and a metadata label. */
    public static MemoryEntry of(String role, String content, String metadata) {
        return new MemoryEntry(role, content, Instant.now(), metadata);
    }
}
