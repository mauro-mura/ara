package io.ara.runtime.wiring;

import java.time.Instant;

/**
 * How a superseded resource version drains its in-flight leases on {@link
 * ResourceRegistry#publish(String, Object, DrainPolicy)} (ADR-039 §5).
 */
public sealed interface DrainPolicy permits DrainPolicy.Graceful, DrainPolicy.Forced {

    /** Convenience constant for the common case. */
    DrainPolicy GRACEFUL = new Graceful();

    /**
     * No eviction. Sessions already pinned to the retired version finish naturally;
     * the version's resource is closed once its ref-count reaches zero. This is the
     * correct behavior for a routine rollout (ADR-039: "una sessione lunga che
     * termina sul vecchio modello è corretta, non un bug").
     */
    record Graceful() implements DrainPolicy { }

    /**
     * Forcibly evicts every lease still active on the retired version at {@code
     * deadline} (or immediately, if {@code deadline} is {@code null}) via {@link
     * Lease#evict()}. Reserved for urgent rollouts (e.g. a leaked credential) where
     * interrupting in-flight work is the intended behavior.
     */
    record Forced(Instant deadline) implements DrainPolicy { }
}
