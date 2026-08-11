package io.ara.runtime.wiring;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reference-counted handle on a resource owned by a {@link ResourceRegistry} (ADR-039).
 *
 * <p>The caller-facing end of the pin/lease model: holding a {@code Lease} keeps the
 * underlying resource version alive; {@link #close()} releases it (decrementing the
 * registry's ref-count for that version, closing the resource if it has been superseded
 * and the count reaches zero). The registry — not this class — owns the counting logic;
 * a {@code Lease} only ever triggers it via {@code onRelease}.
 *
 * <p>{@link #close()} and {@link #evict()} are independent actions with independent
 * idempotency: a {@code DrainPolicy.Forced} eviction and a normal end-of-session
 * teardown can race for the same lease, and each must fire its own callback exactly
 * once regardless of what the other does.
 */
public final class Lease<R> implements AutoCloseable {

    private final R resource;
    private final Runnable onRelease;
    private final AtomicReference<Runnable> onEvict;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean evicted  = new AtomicBoolean(false);

    public Lease(R resource, Runnable onRelease) {
        this(resource, onRelease, () -> { });
    }

    public Lease(R resource, Runnable onRelease, Runnable onEvict) {
        this.resource  = Objects.requireNonNull(resource,  "resource must not be null");
        this.onRelease = Objects.requireNonNull(onRelease, "onRelease must not be null");
        this.onEvict   = new AtomicReference<>(Objects.requireNonNull(onEvict, "onEvict must not be null"));
    }

    /** Returns the leased resource. */
    public R get() {
        return resource;
    }

    /**
     * Attaches (or replaces) the action run by {@link #evict()}. Used by whoever owns the
     * eviction semantics for this lease (e.g. {@code SessionManager} binding a session's
     * cooperative cancellation) — the registry itself stays unaware of what eviction means
     * to the owner (ADR-039 layering guardrail: registry never depends on session).
     */
    public void bindEvictionTarget(Runnable action) {
        onEvict.set(Objects.requireNonNull(action, "action must not be null"));
    }

    /** Releases this lease, decrementing the registry's ref-count. Idempotent. */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            onRelease.run();
        }
    }

    /**
     * Triggers eviction of the lease holder — used by {@code DrainPolicy.Forced} to sever a
     * session still pinned to a retired resource version. Never itself decrements the
     * ref-count or closes the resource: it is cancellation, not a config mutation
     * (ADR-039 §"DrainPolicy.FORCED è cancellazione, non violazione di consistenza").
     * Idempotent, independently of {@link #close()}.
     */
    public void evict() {
        if (evicted.compareAndSet(false, true)) {
            onEvict.get().run();
        }
    }
}
