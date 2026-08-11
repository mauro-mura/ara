package io.ara.core.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local {@link SessionStore}: two concurrent maps, no external I/O. */
final class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<SessionId, Map<String, Object>> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SessionId, CopyOnWriteArrayList<ConversationTurn>> turns = new ConcurrentHashMap<>();

    @Override
    public void saveState(SessionId sessionId, Map<String, Object> stateSnapshot) {
        Objects.requireNonNull(stateSnapshot, "stateSnapshot must not be null");
        state.put(sessionId, Map.copyOf(stateSnapshot));
    }

    @Override
    public Map<String, Object> loadState(SessionId sessionId) {
        return state.getOrDefault(sessionId, Map.of());
    }

    /**
     * Appends in place on a copy-on-write list instead of rebuilding an immutable list.
     *
     * <p>The previous {@code new ArrayList<>(existing)} + {@code List.copyOf(updated)}
     * pairing copied the whole history <em>twice</em> per turn. {@link
     * CopyOnWriteArrayList#add} copies it once, and {@link #loadTurns} still hands out an
     * immutable snapshot, so callers keep the same guarantee.
     */
    @Override
    public void appendTurn(SessionId sessionId, ConversationTurn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        turns.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>()).add(turn);
    }

    /**
     * Returns an immutable snapshot. Taken here rather than stored: the backing list is
     * copy-on-write, so an iterator handed straight to a caller would silently keep showing
     * the history as it was when the iterator was created.
     */
    @Override
    public List<ConversationTurn> loadTurns(SessionId sessionId) {
        List<ConversationTurn> stored = turns.get(sessionId);
        return stored == null ? List.of() : List.copyOf(stored);
    }

    @Override
    public void delete(SessionId sessionId) {
        state.remove(sessionId);
        turns.remove(sessionId);
    }
}
