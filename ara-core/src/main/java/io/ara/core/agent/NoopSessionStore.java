package io.ara.core.agent;

import java.util.List;
import java.util.Map;

/** No-op {@link SessionStore}: discards every write, always reads empty. The default. */
final class NoopSessionStore implements SessionStore {

    static final NoopSessionStore INSTANCE = new NoopSessionStore();

    private NoopSessionStore() {}

    @Override
    public void saveState(SessionId sessionId, Map<String, Object> stateSnapshot) {
        // discarded
    }

    @Override
    public Map<String, Object> loadState(SessionId sessionId) {
        return Map.of();
    }

    @Override
    public void appendTurn(SessionId sessionId, ConversationTurn turn) {
        // discarded
    }

    @Override
    public List<ConversationTurn> loadTurns(SessionId sessionId) {
        return List.of();
    }

    @Override
    public void delete(SessionId sessionId) {
        // nothing to delete
    }
}
