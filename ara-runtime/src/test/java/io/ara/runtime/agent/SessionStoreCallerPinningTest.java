package io.ara.runtime.agent;

import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.RunState;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.runtime.concurrency.PinningProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 N1 row 7, §4 U27 — regression tests for
 * {@code PersistingRunState} (a package-private {@code ara-core} class, exercised here only
 * through the public {@link RunState#persisting} factory) and {@link ConversationHistory}.
 *
 * <p>Lives in {@code ara-runtime}, not {@code ara-core}, purely because {@link PinningProbe}
 * does — {@code ara-core} has no dependency on {@code ara-runtime} (see the root {@code
 * CLAUDE.md}'s module rules) — but both classes under test are driven here through public API
 * only, so this is an ordinary black-box test regardless of which module hosts it.
 *
 * <p>Both classes block on a caller-supplied {@link SessionStore} while holding a lock; a real,
 * durable implementation could do I/O there (see {@link SessionStore}'s own javadoc for the
 * non-blocking constraint that places on it). Before U27, both locks were monitors
 * ({@code synchronized}) — on this project's JDK 21 target that pins the carrier of whoever
 * blocks while holding one.
 */
class SessionStoreCallerPinningTest {

    /** Blocks on a per-{@code sessionId} latch the test controls; everything else is a no-op. */
    private static final class GatedSessionStore implements SessionStore {
        private final Map<String, CountDownLatch> gates;

        GatedSessionStore(Map<String, CountDownLatch> gates) {
            this.gates = gates;
        }

        @Override
        public void saveState(SessionId sessionId, Map<String, Object> stateSnapshot) {
            awaitGate(sessionId);
        }

        @Override
        public Map<String, Object> loadState(SessionId sessionId) {
            return Map.of();
        }

        @Override
        public void appendTurn(SessionId sessionId, ConversationTurn turn) {
            awaitGate(sessionId);
        }

        @Override
        public List<ConversationTurn> loadTurns(SessionId sessionId) {
            return List.of();
        }

        @Override
        public void delete(SessionId sessionId) {
        }

        private void awaitGate(SessionId sessionId) {
            CountDownLatch gate = gates.get(sessionId.value());
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void persistingRunStatePut_withASlowStore_doesNotPinAnyCarrier() throws InterruptedException {
        Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        SessionStore store = new GatedSessionStore(gates);

        int cpus = Runtime.getRuntime().availableProcessors();
        List<PinningProbe.Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < cpus + 2; i++) {
            String id = "rs-" + i;
            RunState state = RunState.persisting(store, SessionId.of(id));
            attempts.add(PinningProbe.Attempt.onLatch(gate -> {
                gates.put(id, gate);
                state.put("k", "v");
            }));
        }

        PinningProbe.Result result = PinningProbe.probe(attempts);

        assertFalse(result.pinningObserved(),
                "N1 (PersistingRunState.put -> SessionStore.saveState) must not pin a carrier "
                        + "after U27 (writeLock is a ReentrantLock, not a monitor)");
    }

    @Test
    void conversationHistoryAddTurn_withASlowStore_doesNotPinAnyCarrier() throws InterruptedException {
        Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        SessionStore store = new GatedSessionStore(gates);

        int cpus = Runtime.getRuntime().availableProcessors();
        List<PinningProbe.Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < cpus + 2; i++) {
            String id = "ch-" + i;
            ConversationHistory history = new ConversationHistory(store, SessionId.of(id));
            attempts.add(PinningProbe.Attempt.onLatch(gate -> {
                gates.put(id, gate);
                history.addTurn("hi", "hello");
            }));
        }

        PinningProbe.Result result = PinningProbe.probe(attempts);

        assertFalse(result.pinningObserved(),
                "N1 (ConversationHistory.addTurn -> SessionStore.appendTurn) must not pin a carrier "
                        + "after U27 (writeLock is a ReentrantLock, not a monitor)");
    }

    /**
     * The read/write split U27 also made in {@link ConversationHistory} (reads no longer share
     * any lock with the writer, not even a non-pinning one): {@code size()} must return
     * promptly while {@code addTurn}'s {@code store.appendTurn} call is still blocked, instead
     * of waiting for it to finish.
     */
    @Test
    void conversationHistorySize_returnsPromptly_whileAddTurnIsBlockedOnASlowStore() throws Exception {
        CountDownLatch appendStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        SessionStore blockingOnce = new SessionStore() {
            private volatile boolean first = true;

            @Override
            public void saveState(SessionId sessionId, Map<String, Object> stateSnapshot) {
            }

            @Override
            public Map<String, Object> loadState(SessionId sessionId) {
                return Map.of();
            }

            @Override
            public void appendTurn(SessionId sessionId, ConversationTurn turn) {
                if (first) {
                    first = false;
                    appendStarted.countDown();
                    try {
                        assertTrue(proceed.await(10, TimeUnit.SECONDS), "test must release the blocked append call");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public List<ConversationTurn> loadTurns(SessionId sessionId) {
                return List.of();
            }

            @Override
            public void delete(SessionId sessionId) {
            }
        };
        ConversationHistory history = new ConversationHistory(blockingOnce, SessionId.of("s1"));

        Thread writer = Thread.ofVirtual().start(() -> history.addTurn("hi", "hello"));
        try {
            assertTrue(appendStarted.await(5, TimeUnit.SECONDS), "addTurn's store.appendTurn call must have started");

            long startNanos = System.nanoTime();
            int size = history.size();   // must not wait for the writer's still-blocked store call
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            assertEquals(1, size, "the turn was already added to the in-memory list before the store call");
            assertTrue(elapsedMs < 2000,
                    "size() must not block behind addTurn's still-in-flight store.appendTurn call "
                            + "(U27: reads never take writeLock), took " + elapsedMs + "ms");
        } finally {
            proceed.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
