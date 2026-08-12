package io.ara.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallMetadataTest {

    @Test
    void callIdAndToolName_areRejectedWhenNull() {
        assertThrows(NullPointerException.class, () -> new ToolCallMetadata(null, "search"));
        assertThrows(NullPointerException.class, () -> new ToolCallMetadata("call-1", null));
    }

    @Test
    void survivesRoundTripThroughMemoryEntry_evenWithCharactersThatWouldBreakStringEncoding() {
        // Regression guard: ToolCallMetadata used to be packed into MemoryEntry.metadata()
        // (a plain String) via a hand-rolled tab-separated encoding — a tab embedded in
        // callId would silently corrupt the round-trip (split on the wrong tab). Now the
        // record is carried directly, with no string encoding step to corrupt at all.
        ToolCallMetadata meta = new ToolCallMetadata("call\t1", "search\tweather");

        MemoryEntry entry = MemoryEntry.of("tool", "42 degrees", meta);

        assertEquals(meta, entry.metadata());
        assertInstanceOf(ToolCallMetadata.class, entry.metadata());
        ToolCallMetadata roundTripped = (ToolCallMetadata) entry.metadata();
        assertEquals("call\t1", roundTripped.callId());
        assertEquals("search\tweather", roundTripped.toolName());
    }

    @Test
    void memoryEntry_withNoMetadata_hasNullMetadata() {
        MemoryEntry entry = MemoryEntry.of("user", "hello");
        assertNull(entry.metadata());
    }

    @Test
    void episodeLabel_isDistinctFromToolCallMetadata_underTheSameSealedInterface() {
        EntryMetadata label = new EpisodeLabel("task_completed");
        MemoryEntry entry = MemoryEntry.of("episode", "did the thing", label);

        assertInstanceOf(EpisodeLabel.class, entry.metadata());
        assertFalse(entry.metadata() instanceof ToolCallMetadata,
                "an EpisodeLabel must not be mistaken for a ToolCallMetadata");
        assertEquals("task_completed", ((EpisodeLabel) entry.metadata()).value());
    }

    @Test
    void episodeLabel_rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new EpisodeLabel(null));
    }
}
