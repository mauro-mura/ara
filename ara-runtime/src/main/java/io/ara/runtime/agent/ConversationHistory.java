package io.ara.runtime.agent;

import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered log of {@link ConversationTurn}s for a single agent session.
 *
 * <p>Turns are stored oldest-first. {@link #recentTurns(int)} returns a
 * tail view bounded by {@code maxTurns} so callers can enforce a token budget
 * without knowing the full history length.
 *
 * <p>Seeded from {@code store.loadTurns(sessionId)} at construction, and every {@link
 * #addTurn} also writes through to {@code store} — with {@link SessionStore#noop()}
 * (the default), this degrades to exactly today's in-memory-only behavior at the cost
 * of one no-op call per turn.
 *
 * <p><strong>Thread-safe.</strong> Writes ({@link #addTurn}) happen on the task thread,
 * serialised by the session's execution lock, but reads do not: {@code
 * AraRuntime.conversationHistory(...)} and {@code SessionManager.listActive()} are
 * read-only observability APIs callable from any thread while a task is running. Every
 * accessor is therefore guarded, and the collection accessors return immutable
 * <em>snapshots</em> rather than views over the backing list — an unmodifiable view is
 * still backed by the live {@code ArrayList} and throws {@link
 * java.util.ConcurrentModificationException} the moment the task thread appends a turn
 * while the observer is iterating.
 */
public final class ConversationHistory {

    /** Guarded by {@code this}. */
    private final List<ConversationTurn> turns;
    private final SessionStore store;
    private final SessionId sessionId;

    public ConversationHistory(SessionStore store, SessionId sessionId) {
        this.store     = Objects.requireNonNull(store, "store must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.turns     = new ArrayList<>(store.loadTurns(sessionId));
    }

    /**
     * Appends a new turn at the end of the history and writes it through to the store.
     * The store write stays inside the lock so that the in-memory order and the persisted
     * order can never diverge.
     */
    public synchronized void addTurn(String userInput, String assistantOutput) {
        ConversationTurn turn = new ConversationTurn(userInput, assistantOutput);
        turns.add(turn);
        store.appendTurn(sessionId, turn);
    }

    /**
     * Returns an immutable snapshot of the most recent {@code maxTurns} turns, ordered
     * oldest-first. If {@code maxTurns <= 0} or the history is empty, returns an empty list.
     */
    public synchronized List<ConversationTurn> recentTurns(int maxTurns) {
        if (maxTurns <= 0 || turns.isEmpty()) return List.of();
        int from = Math.max(0, turns.size() - maxTurns);
        return List.copyOf(turns.subList(from, turns.size()));
    }

    /** Returns an immutable snapshot of every recorded turn, oldest-first. */
    public synchronized List<ConversationTurn> allTurns() {
        return List.copyOf(turns);
    }

    /** Total number of turns recorded so far. */
    public synchronized int size() {
        return turns.size();
    }

    /** Resets the in-memory history (e.g. on explicit session invalidation). */
    public synchronized void clear() {
        turns.clear();
    }
}
