package io.ara.runtime.memory;

import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P5 row (SlidingWindowMemoryManager
 * offload), §4 U18 — regression test.
 *
 * <p>{@code offloadBeforeDiscard}'s per-entry {@code embed} loop, and its {@code
 * upsertAll} call, used to ignore the calling thread's interrupt status entirely: a
 * deadline watchdog interrupting this thread (the same mechanism already wrapping this
 * thread's LLM calls elsewhere) would not stop the loop from working through every
 * remaining entry, one multi-second remote round-trip at a time, nor stop the final batch
 * write. This does not give {@code SlidingWindowMemoryManager} a deadline of its own —
 * {@code MemoryManager} has no such parameter — it only makes an <em>already-interrupted</em>
 * thread's interruption actually stop further remote calls here, instead of being
 * silently ignored.
 */
class SlidingWindowMemoryManagerOffloadInterruptTest {

    /** Records every upsertAll call and every entry within it. */
    private static final class RecordingStore implements SemanticStore {
        final List<String> upserted = new ArrayList<>();
        int upsertAllCalls = 0;

        @Override public void upsert(String agentId, String role, String type, String content, List<Float> vector) {
            upserted.add(content);
        }
        @Override public void upsertAll(String agentId, List<io.ara.core.memory.SemanticEntry> entries) {
            upsertAllCalls++;
            entries.forEach(e -> upserted.add(e.content()));
        }
        @Override public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
            return List.of();
        }
    }

    private static void fill(SlidingWindowMemoryManager m, int n) {
        for (int i = 0; i < n; i++) {
            m.appendToWorkingMemory("user", "message number " + i + " with padding text to burn through the token budget");
        }
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();   // test hygiene: never leak an interrupt into the next test
    }

    @Test
    void offload_neverCallsEmbedOnceTheCallingThreadIsAlreadyInterrupted() {
        AtomicInteger embedCalls = new AtomicInteger();
        EmbeddingClient countingEmbed = new EmbeddingClient() {
            @Override public List<Float> embed(String text) {
                embedCalls.incrementAndGet();
                return List.of(0.1f, 0.2f, 0.3f);
            }
            @Override public int dimensions() { return 3; }
        };
        RecordingStore store = new RecordingStore();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                60, EvictionPolicy.DROP_MIDDLE, null, store, countingEmbed, "agent-1");

        Thread.currentThread().interrupt();   // simulates an external deadline watchdog having already fired
        fill(m, 12);   // budget of 60 tokens forces DROP_MIDDLE eviction, which offloads the evicted range

        assertEquals(0, embedCalls.get(),
                "an already-interrupted thread must not start a single embed() call");
        assertEquals(0, store.upsertAllCalls,
                "an already-interrupted thread must not reach upsertAll either");
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag itself must be left alone, not swallowed");
    }

    @Test
    void offload_uninterrupted_stillOffloadsNormally() {
        AtomicInteger embedCalls = new AtomicInteger();
        EmbeddingClient countingEmbed = new EmbeddingClient() {
            @Override public List<Float> embed(String text) {
                embedCalls.incrementAndGet();
                return List.of(0.1f, 0.2f, 0.3f);
            }
            @Override public int dimensions() { return 3; }
        };
        RecordingStore store = new RecordingStore();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                60, EvictionPolicy.DROP_MIDDLE, null, store, countingEmbed, "agent-1");

        fill(m, 12);

        assertTrue(embedCalls.get() > 0, "uninterrupted eviction must still offload the evicted range");
        assertTrue(store.upsertAllCalls > 0, "uninterrupted eviction must still write the offloaded range");
    }
}
