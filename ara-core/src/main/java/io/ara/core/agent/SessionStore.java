package io.ara.core.agent;

import java.util.List;
import java.util.Map;

/**
 * Durable backing for {@link RunState} and conversation history, keyed by {@link
 * SessionId}. Owned by the runtime (wired via {@code AraRuntime.Builder}, a
 * runtime-wide collaborator like {@code AraTelemetry} — not per-agent config), not
 * per-agent, since *where* a session persists is an infrastructure decision.
 *
 * <p>Write-through, whole-snapshot semantics: {@link #saveState} always receives the
 * complete current state map, never a delta, and every {@link RunState#put}/{@link
 * RunState#merge} on a {@link RunState#persisting(SessionStore, SessionId)} instance
 * calls it immediately — simpler to implement correctly for a real backend, at the
 * cost of some redundant writes, a deliberate simplicity-over-performance tradeoff.
 *
 * <p>Always present, never {@code null} — same null-object idiom as {@link
 * RunState#noop()} / {@code AraTelemetry.noop()}: {@link #noop()} is the default,
 * writes discarded, reads always empty, so callers never branch on "is a store
 * configured". No real durable implementation (e.g. a database-backed one) is
 * provided here — {@link #inMemory()} is a process-local reference implementation
 * only, useful for testing session resumption without wiring an external backend.
 */
public interface SessionStore {

    /** Persists the complete current state snapshot for {@code sessionId}, replacing whatever was stored before. */
    void saveState(SessionId sessionId, Map<String, Object> stateSnapshot);

    /** Returns the persisted state snapshot for {@code sessionId}, or an empty map if none exists. */
    Map<String, Object> loadState(SessionId sessionId);

    /** Appends {@code turn} to the persisted conversation history for {@code sessionId}. */
    void appendTurn(SessionId sessionId, ConversationTurn turn);

    /** Returns the persisted conversation turns for {@code sessionId}, oldest-first — empty if none exist. */
    List<ConversationTurn> loadTurns(SessionId sessionId);

    /** Removes everything persisted for {@code sessionId} (state and turns). */
    void delete(SessionId sessionId);

    /** A process-local, in-memory reference implementation — not durable across a JVM restart. */
    static SessionStore inMemory() {
        return new InMemorySessionStore();
    }

    /** Discards every write and always reads empty — zero overhead, the default. */
    static SessionStore noop() {
        return NoopSessionStore.INSTANCE;
    }
}
