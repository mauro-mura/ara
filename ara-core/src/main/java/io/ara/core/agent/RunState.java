package io.ara.core.agent;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Mutable, thread-safe, session-scoped working state shared by every agent, tool, and
 * pipeline step involved in a session — the ara equivalent of Agno's
 * {@code run_context.session_state}.
 *
 * <p>Owned by {@link io.ara.core.agent.SessionId}'s runtime counterpart (an
 * {@code AgentSession} in {@code ara-runtime}) and reached through {@link
 * RunContext#state()}, since {@code RunContext} is what already flows through every
 * tool call, strategy, and delegation hop. Unlike {@link RunContext#promptVars()} and
 * {@link RunContext#opaque()} — which are immutable per-request snapshots — this state
 * is mutated in place and persists across multiple {@code execute()} calls on the same
 * session.
 *
 * <p><strong>Never auto-interpolated into a prompt</strong>, by the same design rule
 * {@link RunContext} already applies to {@code opaque}: a value here must be explicitly
 * projected into a prompt (e.g. via a dedicated {@code PromptShaper}), never silently
 * substituted, so a stray credential or internal bookkeeping value placed here cannot
 * leak into an LLM's context window by accident.
 */
public interface RunState {

    /**
     * Returns the value stored under {@code key}, cast to {@code type}.
     *
     * <p>Returns {@link Optional#empty()} — not a {@link ClassCastException} — when a
     * value exists under {@code key} but isn't assignable to {@code type}. State keys
     * are a shared namespace across an entire delegation chain; a stale or mismatched
     * type there is expected wear as a system evolves, not a bug worth crashing the
     * caller over.
     */
    <T> Optional<T> get(String key, Class<T> type);

    /** Stores {@code value} under {@code key}, replacing whatever was there before. */
    void put(String key, Object value);

    /**
     * Atomically combines {@code value} with whatever is currently stored under
     * {@code key} using {@code remappingFunction} — the primitive for safe concurrent
     * accumulation (e.g. appending to a list) when multiple tool calls may run on
     * virtual threads in parallel. Mirrors {@link java.util.Map#merge}.
     */
    <T> T merge(String key, T value, BiFunction<? super T, ? super T, ? extends T> remappingFunction);

    /** Returns an immutable point-in-time copy of every entry currently stored. */
    Map<String, Object> snapshot();

    /** A fresh, independent, in-memory state store. */
    static RunState inMemory() {
        return new InMemoryRunState();
    }

    /** A state store that discards every write and never returns a value — zero overhead when unused. */
    static RunState noop() {
        return NoopRunState.INSTANCE;
    }

    /**
     * A state store that reads through to {@code base} when a key is absent from
     * {@code own}, but writes exclusively to {@code own} — {@code base} is never
     * mutated. Used to give a delegated sub-agent visibility into its caller's state
     * without letting it corrupt the caller's own working state.
     */
    static RunState overlay(RunState base, RunState own) {
        return new OverlayRunState(base, own);
    }

    /**
     * A state store seeded from {@code store.loadState(sessionId)} and write-through:
     * every {@link #put}/{@link #merge} also persists the full snapshot back to
     * {@code store}. Used by {@code AgentSession} in place of {@link #inMemory()} when
     * a {@link SessionStore} is configured, so state survives beyond the session's own
     * in-process lifetime.
     */
    static RunState persisting(SessionStore store, SessionId sessionId) {
        RunState delegate = inMemory();
        store.loadState(sessionId).forEach(delegate::put);
        return new PersistingRunState(delegate, store, sessionId);
    }
}
