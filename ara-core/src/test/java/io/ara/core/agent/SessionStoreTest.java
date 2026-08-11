package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    // ── inMemory() ──────────────────────────────────────────────────────────

    @Test
    void inMemory_saveThenLoadState_roundTrips() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");

        store.saveState(id, Map.of("k", "v"));

        assertEquals(Map.of("k", "v"), store.loadState(id));
    }

    @Test
    void inMemory_loadState_unknownSession_returnsEmpty() {
        assertTrue(SessionStore.inMemory().loadState(SessionId.of("never-seen")).isEmpty());
    }

    @Test
    void inMemory_saveState_replacesPreviousSnapshot_notMerges() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");

        store.saveState(id, Map.of("a", "1"));
        store.saveState(id, Map.of("b", "2"));

        assertEquals(Map.of("b", "2"), store.loadState(id), "saveState is whole-snapshot, not a merge");
    }

    @Test
    void inMemory_appendTurn_thenLoadTurns_preservesOrder() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");

        store.appendTurn(id, new ConversationTurn("first", "reply-1"));
        store.appendTurn(id, new ConversationTurn("second", "reply-2"));

        List<ConversationTurn> turns = store.loadTurns(id);
        assertEquals(2, turns.size());
        assertEquals("first", turns.get(0).userInput());
        assertEquals("second", turns.get(1).userInput());
    }

    @Test
    void inMemory_loadTurns_unknownSession_returnsEmpty() {
        assertTrue(SessionStore.inMemory().loadTurns(SessionId.of("never-seen")).isEmpty());
    }

    @Test
    void inMemory_delete_clearsBothStateAndTurns() {
        SessionStore store = SessionStore.inMemory();
        SessionId id = SessionId.of("s1");
        store.saveState(id, Map.of("k", "v"));
        store.appendTurn(id, new ConversationTurn("hi", "hello"));

        store.delete(id);

        assertTrue(store.loadState(id).isEmpty());
        assertTrue(store.loadTurns(id).isEmpty());
    }

    @Test
    void inMemory_sessionsAreIsolated() {
        SessionStore store = SessionStore.inMemory();
        store.saveState(SessionId.of("s1"), Map.of("k", "s1-value"));
        store.saveState(SessionId.of("s2"), Map.of("k", "s2-value"));

        assertEquals("s1-value", store.loadState(SessionId.of("s1")).get("k"));
        assertEquals("s2-value", store.loadState(SessionId.of("s2")).get("k"));
    }

    // ── noop() ──────────────────────────────────────────────────────────────

    @Test
    void noop_saveState_isDiscarded() {
        SessionStore store = SessionStore.noop();
        SessionId id = SessionId.of("s1");
        store.saveState(id, Map.of("k", "v"));
        assertTrue(store.loadState(id).isEmpty());
    }

    @Test
    void noop_appendTurn_isDiscarded() {
        SessionStore store = SessionStore.noop();
        SessionId id = SessionId.of("s1");
        store.appendTurn(id, new ConversationTurn("hi", "hello"));
        assertTrue(store.loadTurns(id).isEmpty());
    }

    @Test
    void noop_delete_doesNotThrow() {
        assertDoesNotThrow(() -> SessionStore.noop().delete(SessionId.of("anything")));
    }
}
