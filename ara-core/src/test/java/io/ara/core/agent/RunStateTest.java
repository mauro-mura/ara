package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies {@link RunState}'s three implementations: in-memory, no-op, and overlay. */
class RunStateTest {

    // ── inMemory() ──────────────────────────────────────────────────────────

    @Test
    void inMemory_put_then_get_returnsValue() {
        RunState state = RunState.inMemory();
        state.put("k", "v");
        assertEquals("v", state.get("k", String.class).orElseThrow());
    }

    @Test
    void inMemory_get_missingKey_returnsEmpty() {
        assertTrue(RunState.inMemory().get("absent", String.class).isEmpty());
    }

    @Test
    void inMemory_get_typeMismatch_returnsEmpty_notClassCastException() {
        RunState state = RunState.inMemory();
        state.put("k", "a string");
        assertTrue(state.get("k", Integer.class).isEmpty());
    }

    @Test
    void inMemory_snapshot_reflectsAllEntries() {
        RunState state = RunState.inMemory();
        state.put("a", 1);
        state.put("b", 2);
        assertEquals(java.util.Map.of("a", 1, "b", 2), state.snapshot());
    }

    @Test
    void inMemory_snapshot_isImmutable() {
        RunState state = RunState.inMemory();
        state.put("a", 1);
        assertThrows(UnsupportedOperationException.class, () -> state.snapshot().put("b", 2));
    }

    @Test
    void inMemory_merge_combinesWithExisting() {
        RunState state = RunState.inMemory();
        state.put("count", 1);
        Integer result = state.merge("count", 1, Integer::sum);
        assertEquals(2, result);
        assertEquals(2, state.get("count", Integer.class).orElseThrow());
    }

    @Test
    void inMemory_merge_absentKey_seedsWithValue() {
        RunState state = RunState.inMemory();
        Integer result = state.merge("count", 5, Integer::sum);
        assertEquals(5, result);
    }

    @Test
    void inMemory_merge_isAtomicUnderConcurrentAppends() throws InterruptedException {
        RunState state = RunState.inMemory();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int item = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    state.merge("items", List.of("item-" + item),
                            (a, b) -> java.util.stream.Stream.concat(a.stream(), b.stream()).toList());
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, errors.get());
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) state.snapshot().get("items");
        assertEquals(threads, items.size(), "no update should be lost to a race");
    }

    // ── noop() ──────────────────────────────────────────────────────────────

    @Test
    void noop_isSingleton() {
        assertSame(RunState.noop(), RunState.noop());
    }

    @Test
    void noop_put_isDiscarded() {
        RunState state = RunState.noop();
        state.put("k", "v");
        assertTrue(state.get("k", String.class).isEmpty());
    }

    @Test
    void noop_merge_returnsValueUnchanged() {
        assertEquals("v", RunState.noop().merge("k", "v", (a, b) -> a + b));
    }

    @Test
    void noop_snapshot_isEmpty() {
        assertTrue(RunState.noop().snapshot().isEmpty());
    }

    // ── overlay() ───────────────────────────────────────────────────────────

    @Test
    void overlay_get_fallsThroughToBase_whenAbsentFromOwn() {
        RunState base = RunState.inMemory();
        base.put("k", "from-base");
        RunState overlay = RunState.overlay(base, RunState.inMemory());
        assertEquals("from-base", overlay.get("k", String.class).orElseThrow());
    }

    @Test
    void overlay_get_prefersOwnOverBase() {
        RunState base = RunState.inMemory();
        base.put("k", "from-base");
        RunState own = RunState.inMemory();
        own.put("k", "from-own");
        RunState overlay = RunState.overlay(base, own);
        assertEquals("from-own", overlay.get("k", String.class).orElseThrow());
    }

    @Test
    void overlay_put_neverMutatesBase() {
        RunState base = RunState.inMemory();
        RunState own = RunState.inMemory();
        RunState overlay = RunState.overlay(base, own);

        overlay.put("k", "written-by-overlay");

        assertTrue(base.get("k", String.class).isEmpty(), "base must be untouched");
        assertEquals("written-by-overlay", own.get("k", String.class).orElseThrow());
    }

    @Test
    void overlay_snapshot_mergesBaseAndOwn_ownWins() {
        RunState base = RunState.inMemory();
        base.put("shared", "base-value");
        base.put("baseOnly", "b");
        RunState own = RunState.inMemory();
        own.put("shared", "own-value");
        own.put("ownOnly", "o");

        var snapshot = RunState.overlay(base, own).snapshot();

        assertEquals("own-value", snapshot.get("shared"));
        assertEquals("b", snapshot.get("baseOnly"));
        assertEquals("o", snapshot.get("ownOnly"));
    }

    // ── persisting() ────────────────────────────────────────────────────────

    @Test
    void persisting_seedsFromStore_atConstruction() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");
        store.saveState(id, java.util.Map.of("k", "seeded"));

        RunState state = RunState.persisting(store, id);

        assertEquals("seeded", state.get("k", String.class).orElseThrow());
    }

    @Test
    void persisting_put_writesThroughToStore() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");
        RunState state = RunState.persisting(store, id);

        state.put("k", "v");

        assertEquals("v", store.loadState(id).get("k"));
    }

    @Test
    void persisting_merge_writesThroughToStore() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");
        RunState state = RunState.persisting(store, id);

        state.merge("count", 1, Integer::sum);
        state.merge("count", 1, Integer::sum);

        assertEquals(2, store.loadState(id).get("count"));
    }

    @Test
    void persisting_survivesANewInstanceOverTheSameStoreAndSession() {
        // Simulates a session resuming after its own RunState instance was discarded
        // (e.g. session evicted, or a fresh AgentSession built later) — the only thing
        // that must survive is the SessionStore + SessionId pair.
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");

        RunState.persisting(store, id).put("k", "first-instance-value");
        RunState resumed = RunState.persisting(store, id);

        assertEquals("first-instance-value", resumed.get("k", String.class).orElseThrow());
    }
}
