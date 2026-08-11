package io.ara.core.agent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * {@link RunState} that delegates reads/writes to an in-memory {@code delegate}, and
 * after every write also pushes the delegate's full snapshot to a {@link SessionStore}
 * — write-through, whole-snapshot (see {@link SessionStore}'s javadoc for why not a
 * delta). Reads never touch the store: the delegate was seeded from it once at
 * construction (see {@link RunState#persisting(SessionStore, SessionId)}), so it
 * already holds everything the store had.
 *
 * <p><strong>Writes are serialised.</strong> {@link #put} and {@link #merge} hold
 * {@code writeLock} across both the delegate mutation and the {@code saveState} that
 * follows it. That pairing has to be atomic: without it, two threads writing different
 * keys — which happens routinely, since a strategy dispatches parallel tool calls on
 * virtual threads and each may write state — can interleave as
 *
 * <pre>
 *   A: delegate.put(k1)   B: delegate.put(k2)
 *   A: snapshot -> {k1}
 *                         B: snapshot -> {k1,k2}
 *                         B: saveState({k1,k2})
 *   A: saveState({k1})    &lt;-- lands last, silently dropping k2 from the store
 * </pre>
 *
 * The in-memory delegate stays correct (it is a concurrent map); it is the <em>store</em>
 * that ends up behind, so the loss only surfaces after a restart or an eviction — the
 * worst kind to debug. Reads ({@link #get}, {@link #snapshot}) stay lock-free: they never
 * touch the store, and the delegate is already thread-safe.
 */
final class PersistingRunState implements RunState {

    private final RunState delegate;
    private final SessionStore store;
    private final SessionId sessionId;
    /** Serialises mutate-then-persist pairs. Dedicated object so callers cannot lock on us. */
    private final Object writeLock = new Object();

    PersistingRunState(RunState delegate, SessionStore store, SessionId sessionId) {
        this.delegate  = Objects.requireNonNull(delegate,  "delegate must not be null");
        this.store     = Objects.requireNonNull(store,     "store must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return delegate.get(key, type);
    }

    @Override
    public void put(String key, Object value) {
        synchronized (writeLock) {
            delegate.put(key, value);
            store.saveState(sessionId, delegate.snapshot());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code remappingFunction} runs while the write lock is held, so it must be quick
     * and must not call back into this state — the same constraint {@link
     * java.util.concurrent.ConcurrentHashMap#merge} already imposes on its own remapping
     * function.
     */
    @Override
    public <T> T merge(String key, T value, BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
        synchronized (writeLock) {
            T result = delegate.merge(key, value, remappingFunction);
            store.saveState(sessionId, delegate.snapshot());
            return result;
        }
    }

    @Override
    public Map<String, Object> snapshot() {
        return delegate.snapshot();
    }
}
