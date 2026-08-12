package io.ara.core.memory;

/**
 * Structured metadata a {@link MemoryEntry} may carry, in place of the single untyped
 * label a hand-rolled string encoding used to force into {@code MemoryEntry.metadata()}
 * (previously a plain {@code String}): a {@link ToolCallMetadata} correlation for
 * tool-call entries, or an {@link EpisodeLabel} sub-type for episodic entries.
 */
public sealed interface EntryMetadata permits ToolCallMetadata, EpisodeLabel {
}
