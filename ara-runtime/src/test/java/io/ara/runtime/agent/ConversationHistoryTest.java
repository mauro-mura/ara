package io.ara.runtime.agent;

import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {

    @Test
    void seedsFromStore_atConstruction() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");
        store.appendTurn(id, new ConversationTurn("earlier", "reply"));

        ConversationHistory history = new ConversationHistory(store, id);

        assertEquals(1, history.size());
        assertEquals("earlier", history.allTurns().get(0).userInput());
    }

    @Test
    void addTurn_writesThroughToStore() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");
        ConversationHistory history = new ConversationHistory(store, id);

        history.addTurn("hi", "hello");

        List<ConversationTurn> persisted = store.loadTurns(id);
        assertEquals(1, persisted.size());
        assertEquals("hi", persisted.get(0).userInput());
        assertEquals("hello", persisted.get(0).assistantOutput());
    }

    @Test
    void noopStore_behavesExactlyAsBeforePersistenceExisted() {
        ConversationHistory history = new ConversationHistory(SessionStore.noop(), SessionId.of("s1"));

        history.addTurn("hi", "hello");

        assertEquals(1, history.size(), "the in-process list still works — only the store side-channel is a no-op");
    }

    @Test
    void recentTurns_boundedByMaxTurns_stillWorksAfterSeeding() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");
        store.appendTurn(id, new ConversationTurn("t1", "r1"));
        store.appendTurn(id, new ConversationTurn("t2", "r2"));
        store.appendTurn(id, new ConversationTurn("t3", "r3"));

        ConversationHistory history = new ConversationHistory(store, id);

        List<ConversationTurn> recent = history.recentTurns(2);
        assertEquals(List.of("t2", "t3"), recent.stream().map(ConversationTurn::userInput).toList());
    }

    /**
     * Regression: {@code allTurns()}/{@code recentTurns()} used to hand back {@code
     * Collections.unmodifiableList} views over the live backing {@code ArrayList}. Those are
     * read-only observability APIs reachable from any thread (e.g. {@code
     * AraRuntime.conversationHistory}) while the task thread appends turns, so iterating one
     * raced the writer straight into a {@link java.util.ConcurrentModificationException}.
     */
    @Test
    void readsAreSafeWhileAnotherThreadAppends() throws Exception {
        ConversationHistory history = new ConversationHistory(SessionStore.noop(), SessionId.of("s1"));
        int writes = 2_000;

        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                started.await();
                for (int i = 0; i < writes; i++) {
                    // Both accessors must survive concurrent structural modification.
                    history.allTurns().forEach(ConversationTurn::userInput);
                    history.recentTurns(5).forEach(ConversationTurn::userInput);
                    history.size();
                }
            } catch (Throwable t) {
                readerFailure.set(t);
            }
        });

        started.countDown();
        for (int i = 0; i < writes; i++) {
            history.addTurn("u" + i, "a" + i);
        }
        reader.join();

        assertNull(readerFailure.get(), "concurrent reads must not throw: " + readerFailure.get());
        assertEquals(writes, history.size());
    }

    @Test
    void returnedListsAreImmutableSnapshots_notLiveViews() {
        ConversationHistory history = new ConversationHistory(SessionStore.noop(), SessionId.of("s1"));
        history.addTurn("first", "r1");

        List<ConversationTurn> snapshot = history.allTurns();
        history.addTurn("second", "r2");

        assertEquals(1, snapshot.size(), "a snapshot must not observe turns added after it was taken");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new ConversationTurn("x", "y")));
    }
}
