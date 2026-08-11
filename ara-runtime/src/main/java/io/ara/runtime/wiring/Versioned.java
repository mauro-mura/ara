package io.ara.runtime.wiring;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Copy-on-write, atomically swappable pointer to the current value of {@code T} (ADR-039).
 *
 * <p>Used for the agent's current {@link io.ara.core.agent.AgentConfig}: {@link #swap}
 * publishes a new value without disturbing any reader that already captured the previous
 * one via {@link #current()}. No lifecycle, no reference counting — that is the job of
 * {@link ResourceRegistry} for shared owned resources; this class only ever holds plain
 * immutable values.
 *
 * <p><strong>Purity requirement:</strong> the {@link UnaryOperator} passed to {@link #swap}
 * must be a pure function of its input. {@link AtomicReference#updateAndGet} may invoke it
 * more than once under contention, so it must never perform a side effect (e.g. publishing
 * a resource to a registry) — see ADR-039 §"Correttezza e casi limite".
 */
public final class Versioned<T> {

    private final AtomicReference<T> current;

    public Versioned(T initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial must not be null"));
    }

    /** Returns the current value. A caller that reads this once and keeps the result never observes a torn update. */
    public T current() {
        return current.get();
    }

    /** Atomically publishes {@code update.apply(current())} as the new current value and returns it. */
    public T swap(UnaryOperator<T> update) {
        Objects.requireNonNull(update, "update must not be null");
        return current.updateAndGet(update);
    }
}
