package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: {@code PersistingRunState.put}/{@code merge} used to call {@code
 * delegate.put(...)} and {@code store.saveState(sessionId, delegate.snapshot())} as two
 * unsynchronised steps. Two threads writing different keys could therefore have their
 * {@code saveState} calls land out of order, leaving the store holding an older snapshot
 * than the one already computed — a silent lost update visible only after a restart or an
 * eviction repopulates the session from the store.
 *
 * <p>{@code RunState} is documented as thread-safe and is written concurrently in practice
 * (a strategy dispatches parallel tool calls on virtual threads, each of which may write
 * state), so this is a reachable path, not a theoretical one.
 */
class PersistingRunStateConcurrencyTest {

    /**
     * A store that makes the interleaving window wide and observable: it records every
     * snapshot it is handed, and yields inside {@code saveState} so a competing writer is
     * very likely to slip between another thread's snapshot and its save.
     */
    private static final class RecordingSlowStore implements SessionStore {
        private final SessionStore delegate = SessionStore.inMemory();
        private final List<Map<String, Object>> saves = new ArrayList<>();
        private final AtomicInteger concurrentSavers = new AtomicInteger();
        private volatile int maxConcurrentSavers = 0;

        @Override
        public void saveState(SessionId sessionId, Map<String, Object> stateSnapshot) {
            maxConcurrentSavers = Math.max(maxConcurrentSavers, concurrentSavers.incrementAndGet());
            try {
                Thread.yield();
                synchronized (saves) { saves.add(stateSnapshot); }
                delegate.saveState(sessionId, stateSnapshot);
            } finally {
                concurrentSavers.decrementAndGet();
            }
        }

        @Override public Map<String, Object> loadState(SessionId id)            { return delegate.loadState(id); }
        @Override public void appendTurn(SessionId id, ConversationTurn turn)   { delegate.appendTurn(id, turn); }
        @Override public List<ConversationTurn> loadTurns(SessionId id)         { return delegate.loadTurns(id); }
        @Override public void delete(SessionId id)                              { delegate.delete(id); }
    }

    @Test
    void concurrentPuts_allSurviveInTheStore_notJustInMemory() throws Exception {
        RecordingSlowStore store = new RecordingSlowStore();
        SessionId sessionId = SessionId.of("s1");
        RunState state = RunState.persisting(store, sessionId);

        int threads = 8;
        int writesPerThread = 40;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        state.put("k-" + threadIndex + "-" + i, threadIndex * 1_000 + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "writers did not finish in time");

        int expected = threads * writesPerThread;
        assertEquals(expected, state.snapshot().size(), "in-memory delegate must hold every key");
        // The real assertion: the store must have ended up with the same thing. Before the
        // fix, a later-but-staler snapshot overwrote a newer one and this came up short.
        assertEquals(expected, store.loadState(sessionId).size(),
                "every write must be durable — a stale snapshot must never overwrite a newer one");
        assertEquals(1, store.maxConcurrentSavers,
                "mutate-then-persist must be serialised: two threads inside saveState for the "
                        + "same state is exactly the window that loses a write");
    }

    @Test
    void concurrentMerges_accumulateWithoutLoss() throws Exception {
        RecordingSlowStore store = new RecordingSlowStore();
        SessionId sessionId = SessionId.of("s2");
        RunState state = RunState.persisting(store, sessionId);

        int threads = 8;
        int incrementsPerThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < incrementsPerThread; i++) {
                        state.merge("counter", 1, (a, b) -> ((Integer) a) + ((Integer) b));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "writers did not finish in time");

        int expected = threads * incrementsPerThread;
        assertEquals(expected, state.get("counter", Integer.class).orElseThrow());
        assertEquals(expected, store.loadState(sessionId).get("counter"),
                "the persisted counter must match the in-memory one");
    }
}
