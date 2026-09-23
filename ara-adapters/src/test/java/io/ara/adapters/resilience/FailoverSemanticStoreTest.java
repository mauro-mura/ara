package io.ara.adapters.resilience;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticEntry;
import io.ara.core.memory.SemanticStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailoverSemanticStoreTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();   // test hygiene: never leak an interrupt into the next test
    }

    /** Records every upsert it receives; never fails. */
    private static final class RecordingStore implements SemanticStore {
        final List<String> upserted = new ArrayList<>();
        final List<List<SemanticEntry>> upsertedAll = new ArrayList<>();

        @Override
        public void upsert(String agentId, String role, String type, String content, List<Float> vector) {
            upserted.add(content);
        }

        @Override
        public void upsertAll(String agentId, List<SemanticEntry> entries) {
            upsertedAll.add(entries);
        }

        @Override
        public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
            return List.of();
        }
    }

    private static SemanticStore alwaysFailing(RuntimeException ex) {
        return new SemanticStore() {
            @Override public void upsert(String a, String r, String t, String c, List<Float> v) { throw ex; }
            @Override public void upsertAll(String a, List<SemanticEntry> entries) { throw ex; }
            @Override public List<MemoryEntry> search(String a, List<Float> q, int limit) { throw ex; }
        };
    }

    // ── writes: fan-out ──────────────────────────────────────────────────────

    @Test
    void upsert_reachesEveryHealthyReplica() {
        RecordingStore r1 = new RecordingStore();
        RecordingStore r2 = new RecordingStore();
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(r1, r2));

        store.upsert("agent", "episode", null, "hello", List.of(1f));

        assertEquals(List.of("hello"), r1.upserted);
        assertEquals(List.of("hello"), r2.upserted);
    }

    @Test
    void upsert_succeedsWhenOnlyOneReplicaFails() {
        RecordingStore healthy = new RecordingStore();
        SemanticStore  down    = alwaysFailing(new RuntimeException("replica down"));
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(down, healthy));

        assertDoesNotThrow(() -> store.upsert("agent", "episode", null, "hello", List.of(1f)));
        assertEquals(List.of("hello"), healthy.upserted);
    }

    @Test
    void upsert_throwsOnlyWhenEveryReplicaFails() {
        SemanticStore down1 = alwaysFailing(new RuntimeException("down1"));
        SemanticStore down2 = alwaysFailing(new RuntimeException("down2"));
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(down1, down2));

        assertThrows(RuntimeException.class,
                () -> store.upsert("agent", "episode", null, "hello", List.of(1f)));
    }

    @Test
    void upsertAll_usesEachReplicasOwnBatchedWrite_notPerEntryUpsert() {
        // If upsertAll fell back to the SemanticStore default (looping upsert per entry), the
        // RecordingStore's upsertedAll list would stay empty and upserted would gain 2 entries
        // instead. Asserting upsertedAll is populated proves the batched path was actually used.
        RecordingStore r1 = new RecordingStore();
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(r1));
        List<SemanticEntry> batch = List.of(
                new SemanticEntry("episode", "evicted_context", "a", List.of(1f)),
                new SemanticEntry("episode", "evicted_context", "b", List.of(2f)));

        store.upsertAll("agent", batch);

        assertEquals(List.of(batch), r1.upsertedAll);
        assertTrue(r1.upserted.isEmpty(), "must not also have gone through the per-entry upsert path");
    }

    // ── reads: ordered failover, never on empty ─────────────────────────────

    @Test
    void search_failsOverToNextReplica_onException() {
        SemanticStore down = alwaysFailing(new RuntimeException("primary down"));
        RecordingStore healthy = new RecordingStore();
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(down, healthy));

        assertEquals(List.of(), store.search("agent", List.of(1f), 5));
    }

    @Test
    void search_doesNotFailOver_onAnEmptyResult() {
        AtomicInteger secondaryCalls = new AtomicInteger();
        SemanticStore primary = new RecordingStore(); // search() returns List.of() by default
        SemanticStore secondary = new SemanticStore() {
            @Override public void upsert(String a, String r, String t, String c, List<Float> v) {}
            @Override public List<MemoryEntry> search(String a, List<Float> q, int limit) {
                secondaryCalls.incrementAndGet();
                return List.of(MemoryEntry.of("episode", "should-not-be-reached"));
            }
        };
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(primary, secondary));

        List<MemoryEntry> result = store.search("agent", List.of(1f), 5);

        assertTrue(result.isEmpty());
        assertEquals(0, secondaryCalls.get(), "an empty primary result must not trigger the fallback");
    }

    @Test
    void search_throwsLastFailure_whenEveryReplicaFails() {
        SemanticStore down1 = alwaysFailing(new RuntimeException("down1"));
        SemanticStore down2 = alwaysFailing(new RuntimeException("down2"));
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(down1, down2));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> store.search("agent", List.of(1f), 5));
        assertEquals("down2", ex.getMessage());
    }

    @Test
    void constructor_rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new FailoverSemanticStore(List.of()));
    }

    // ── P8/U21 — interrupt stops search() failover, never fanOut() (different policy) ──

    @Test
    void search_stopsTheLoopAfterAnInterruptedReplica_doesNotTryTheRemainingOnes() {
        AtomicInteger callsB = new AtomicInteger();
        AtomicInteger callsC = new AtomicInteger();
        SemanticStore a = alwaysFailing(new RuntimeException("a down"));
        SemanticStore b = new SemanticStore() {
            @Override public void upsert(String ag, String r, String t, String c, List<Float> v) {}
            @Override public List<MemoryEntry> search(String ag, List<Float> q, int limit) {
                callsB.incrementAndGet();
                throw new RuntimeException("b down");
            }
        };
        SemanticStore c = new SemanticStore() {
            @Override public void upsert(String ag, String r, String t, String c, List<Float> v) {}
            @Override public List<MemoryEntry> search(String ag, List<Float> q, int limit) {
                callsC.incrementAndGet();
                return List.of(MemoryEntry.of("episode", "should-not-be-reached"));
            }
        };

        // Simulates a deadline watchdog firing while replica 'a' was running: by the
        // time search() gets control back, the calling thread is already interrupted.
        Thread.currentThread().interrupt();

        FailoverSemanticStore store = new FailoverSemanticStore(List.of(a, b, c));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> store.search("agent", List.of(1f), 5));

        assertEquals("a down", ex.getMessage(), "must throw the first (only tried) replica's failure");
        assertEquals(0, callsB.get(), "the loop must stop after the interrupted check — 'b' must never run");
        assertEquals(0, callsC.get(), "'c' must never run either");
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag itself must be left alone, not swallowed");
    }

    @Test
    void search_uninterrupted_stillFailsOverNormally() {
        SemanticStore down = alwaysFailing(new RuntimeException("primary down"));
        RecordingStore healthy = new RecordingStore();
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(down, healthy));

        assertEquals(List.of(), store.search("agent", List.of(1f), 5));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void upsert_fanOut_ignoresInterrupt_stillReachesEveryReplica() {
        // fanOut() is a write-broadcast, a different policy from search()'s stop-at-first-success
        // failover (see the class javadoc) — an interrupt must not cut a write short and leave
        // some replicas silently un-written.
        RecordingStore r1 = new RecordingStore();
        RecordingStore r2 = new RecordingStore();
        FailoverSemanticStore store = new FailoverSemanticStore(List.of(r1, r2));

        Thread.currentThread().interrupt();
        store.upsert("agent", "episode", null, "hello", List.of(1f));

        assertEquals(List.of("hello"), r1.upserted);
        assertEquals(List.of("hello"), r2.upserted, "fanOut() must still reach every replica even when interrupted");
    }
}
