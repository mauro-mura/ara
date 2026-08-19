package io.ara.runtime.memory;

import io.ara.core.media.MediaRef;
import io.ara.core.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The regression this whole design exists for.
 *
 * <p>With bytes inline, a 2 MB PDF is ~2.7 million base64 characters, which the char-based
 * estimate reads as ~680k tokens. Against any realistic budget the eviction loop then runs
 * until only one entry is left — and the survivor is the document, so the agent loses its own
 * system prompt and keeps the base64. No eviction policy fixes that, because the problem was
 * the estimate, not the policy. Holding a reference instead makes the entry small enough that
 * the question never arises.
 */
class SlidingWindowMediaTest {

    /** A reference to a 2 MB PDF — the size that used to empty the window. */
    private static final MediaRef BIG_PDF =
            new MediaRef("digest-1", "application/pdf", "contract.pdf", 2 * 1024 * 1024, null);

    @Test
    void a_two_megabyte_attachment_does_not_empty_the_window() {
        SlidingWindowMemoryManager memory =
                new SlidingWindowMemoryManager(8_000, EvictionPolicy.DROP_MIDDLE);

        memory.appendToWorkingMemory("system", "You are a contract analyst.");
        memory.appendToWorkingMemory("user", "check the recess clause", List.of(BIG_PDF));

        List<MemoryEntry> window = memory.workingMemory();
        assertEquals(2, window.size(), "nothing should have been evicted");
        assertEquals("system", window.get(0).role(), "the system prompt must survive");
        assertEquals(List.of(BIG_PDF), window.get(1).media());
    }

    @Test
    void media_is_charged_a_flat_amount_regardless_of_payload_size() {
        MediaRef tinyPdf = new MediaRef("digest-2", "application/pdf", "note.pdf", 900, null);

        // A budget that fits the text of both entries but only one document's flat charge.
        SlidingWindowMemoryManager memory =
                new SlidingWindowMemoryManager(7_000, EvictionPolicy.DROP_OLDEST);
        memory.appendToWorkingMemory("system", "sys");
        memory.appendToWorkingMemory("user", "one", List.of(tinyPdf));
        int afterOne = memory.workingMemory().size();

        memory.appendToWorkingMemory("user", "two", List.of(BIG_PDF));

        assertEquals(2, afterOne, "one document fits the budget");
        assertTrue(memory.workingMemory().size() < 3,
                "a second document exceeds it — the 900-byte and the 2 MB PDF cost the same");
    }

    @Test
    void eviction_never_separates_an_entrys_text_from_its_media() {
        SlidingWindowMemoryManager memory =
                new SlidingWindowMemoryManager(2_000, EvictionPolicy.DROP_OLDEST);

        memory.appendToWorkingMemory("system", "sys");
        memory.appendToWorkingMemory("user", "look at this", List.of(BIG_PDF));
        memory.appendToWorkingMemory("assistant", "the clause is compliant");

        // Whatever survived, no entry can hold media without the text it came with, or text
        // whose media was dropped: they are one record, so eviction takes both or neither.
        for (MemoryEntry entry : memory.workingMemory()) {
            if (entry.hasMedia()) {
                assertEquals("look at this", entry.content(),
                        "the attachment must still be on the message it arrived with");
            }
        }
    }

    @Test
    void text_only_entries_are_estimated_exactly_as_before() {
        SlidingWindowMemoryManager withMediaSupport =
                new SlidingWindowMemoryManager(20, EvictionPolicy.DROP_OLDEST);
        withMediaSupport.appendToWorkingMemory("system", "a".repeat(40));
        withMediaSupport.appendToWorkingMemory("user", "b".repeat(40));

        // 40 chars + role ≈ 11 tokens each, so two entries exceed a 20-token budget by one.
        assertEquals(1, withMediaSupport.workingMemory().size());
    }
}
