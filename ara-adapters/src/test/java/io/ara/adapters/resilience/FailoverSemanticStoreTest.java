package io.ara.adapters.resilience;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticEntry;
import io.ara.core.memory.SemanticStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailoverSemanticStoreTest {

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
}
