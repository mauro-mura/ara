package io.ara.runtime.factory;

import io.ara.core.llm.LlmException;
import io.ara.runtime.factory.CircuitBreakerLlmClient.State;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The health of one LLM endpoint, shared by every {@link CircuitBreakerLlmClient} wrapping it.
 *
 * <h2>Why the state is not in the breaker</h2>
 * <p>ARA pins an {@code AgentWiring} — and with it the breaker wrapper — to a session, while the
 * transport underneath is leased from a shared, ref-counted registry. Keeping the state inside the
 * wrapper therefore gave every session its own private opinion about an endpoint they all share:
 * with the default threshold of 3, an outage cost {@code 3 × sessions} real timeouts to open every
 * circuit, and — worse — each <em>new</em> session started CLOSED and had to re-learn the outage
 * with 3 more timeouts of its own, indefinitely while the outage lasted. That directly contradicted
 * the breaker's own promise of paying a dead endpoint's timeout once rather than once per request.
 * Health is a property of the endpoint, not of a conversation, so it lives here and the wrapper
 * stays stateless.
 *
 * <p>The sharing key is the registry's {@code transportId} (see {@link CircuitStateRegistry}), the
 * same key the transport itself is shared by, which makes the health grain exactly the resource
 * grain. Keying by {@code providerId()} was rejected: it is {@code "openai-" + modelName}, so two
 * distinct gateways serving the same model would collapse onto one key and an outage on one would
 * open the circuit for the other, which is healthy. Keying by {@code baseUrl} was rejected for the
 * mirror-image reason: one dead model (removed, not pulled, separately rate-limited) would open the
 * circuit for every healthy model on that host. Losing working capacity is worse than re-learning
 * an endpoint-wide outage once per model, which is the bounded cost this grain accepts.
 *
 * <h2>Thread safety</h2>
 * <p>Thread-safe, and now genuinely contended: sessions share one instance. The three fields of the
 * machine (state, consecutive failures, opened-at) move together in a single immutable
 * {@link Snapshot} behind one {@link AtomicReference}, so every update is one compound
 * compare-and-set. Three independent atomics — what the per-session wrapper used — could interleave
 * a success with two failures and make the threshold non-deterministic; harmless when only one
 * session touched them, a real race once they are shared. Configuration is final and travels with
 * the state, so every sharer of an endpoint agrees on the threshold and cooldown by construction.
 */
final class CircuitState {

    /** Immutable triple: the whole machine moves from one of these to the next atomically. */
    private record Snapshot(State state, int consecutiveFailures, long openedAtMillis) { }

    /** What the caller must do about a call it is holding, decided by {@link #beforeCall()}. */
    enum Decision {
        /** Circuit closed — call the delegate. */
        PROCEED,
        /** Open, or a trial already in flight — fast-fail without touching the delegate. */
        SKIP,
        /** Cooldown elapsed and this caller won the single trial — call the delegate, record the transition. */
        TRIAL_CLAIMED
    }

    private final int      failureThreshold;
    private final Duration cooldown;
    private final Clock    clock;

    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(State.CLOSED, 0, -1L));

    CircuitState(int failureThreshold, Duration cooldown, Clock clock) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (cooldown.isZero() || cooldown.isNegative())
            throw new IllegalArgumentException("cooldown must be positive");
        this.failureThreshold = failureThreshold;
        this.cooldown         = cooldown;
        this.clock            = Objects.requireNonNull(clock, "clock must not be null");
    }

    int failureThreshold() {
        return failureThreshold;
    }

    Duration cooldown() {
        return cooldown;
    }

    State state() {
        return snapshot.get().state();
    }

    /**
     * Decides whether the call may proceed, and claims the single half-open trial when the cooldown
     * has elapsed. The CAS loop retries only when another thread changed the snapshot underneath,
     * so a lost race is re-evaluated against fresh state rather than acting on a stale read.
     */
    Decision beforeCall() {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.state() == State.CLOSED)    return Decision.PROCEED;
            if (current.state() == State.HALF_OPEN) return Decision.SKIP;
            if (clock.millis() - current.openedAtMillis() < cooldown.toMillis()) return Decision.SKIP;
            Snapshot trial = new Snapshot(State.HALF_OPEN, current.consecutiveFailures(),
                    current.openedAtMillis());
            if (snapshot.compareAndSet(current, trial)) return Decision.TRIAL_CLAIMED;
        }
    }

    /**
     * Records a successful call, closing the circuit and clearing the failure count.
     *
     * @return the state recovered from, when this success was a transition worth a span; empty when
     *         the circuit was already closed, which is the normal path and would otherwise emit one
     *         span per request forever
     */
    Optional<State> onSuccess() {
        while (true) {
            Snapshot current = snapshot.get();
            Snapshot closed  = new Snapshot(State.CLOSED, 0, current.openedAtMillis());
            if (snapshot.compareAndSet(current, closed)) {
                return current.state() == State.CLOSED ? Optional.empty() : Optional.of(current.state());
            }
        }
    }

    /**
     * Records a failed call. A failed half-open trial reopens immediately — one probe, one verdict,
     * another cooldown — however many failures had accumulated before. A deterministic failure
     * (401, invalid request, content filter) is ignored entirely: it would recur identically on
     * every candidate in the pool, so counting it would let a misconfiguration monopolise the
     * fallback.
     *
     * @return the state the circuit opened from, when it opened; empty when the failure was only
     *         counted or was ignored
     */
    Optional<State> onFailure(Throwable failure) {
        if (failure instanceof LlmException llmFailure && !llmFailure.shouldFailover()) {
            return Optional.empty();
        }
        while (true) {
            Snapshot current = snapshot.get();
            if (current.state() == State.HALF_OPEN) {
                if (snapshot.compareAndSet(current, openedNow())) return Optional.of(State.HALF_OPEN);
                continue;
            }
            int failures = current.consecutiveFailures() + 1;
            if (failures >= failureThreshold) {
                if (snapshot.compareAndSet(current, openedNow())) return Optional.of(current.state());
                continue;
            }
            Snapshot counted = new Snapshot(current.state(), failures, current.openedAtMillis());
            if (snapshot.compareAndSet(current, counted)) return Optional.empty();
        }
    }

    /**
     * Releases a half-open trial that was claimed but never resolved — a streaming subscription the
     * caller cancelled before any terminal signal. Without this the trial would stay in flight
     * forever and every later call would fast-fail a possibly-recovered endpoint.
     *
     * @return {@link State#HALF_OPEN} when this call reopened the circuit; empty when the trial had
     *         already resolved on its own, so a cancel arriving after a normal completion is a no-op
     */
    Optional<State> onTrialAbandoned() {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.state() != State.HALF_OPEN) return Optional.empty();
            if (snapshot.compareAndSet(current, openedNow())) return Optional.of(State.HALF_OPEN);
        }
    }

    private Snapshot openedNow() {
        return new Snapshot(State.OPEN, 0, clock.millis());
    }
}
