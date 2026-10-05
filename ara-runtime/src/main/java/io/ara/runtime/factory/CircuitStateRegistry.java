package io.ara.runtime.factory;

import io.ara.core.llm.LlmClient;
import io.ara.core.telemetry.AraTelemetry;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runtime-wide catalog of per-endpoint circuit health, keyed by the registry {@code transportId}.
 *
 * <p>One instance lives next to the shared {@code llmTransports} registry in {@code AgentFactory},
 * which is what gives it the scope that matters: the transport for an endpoint is already shared by
 * every session and agent, so its health is shared by exactly the same set. A session-scoped
 * breaker that owned its state made each session re-learn an outage on its own — see
 * {@link CircuitState} for the cost that imposed.
 *
 * <p>Entries are never evicted. A {@code transportId} is a configured endpoint, so the key space is
 * the size of the deployment's configuration — a handful — not of its traffic. An eviction policy
 * would add a lifecycle (and a way to forget an outage that is still happening) for no bounded
 * growth to control.
 *
 * <p><b>Thread safety:</b> thread-safe. The map is a {@link ConcurrentHashMap} and
 * {@link #stateFor} uses {@code computeIfAbsent} with a mapping function that only allocates — no
 * I/O, no lock acquisition — so it never blocks inside the map's bin lock.
 */
public final class CircuitStateRegistry {

    private final ConcurrentMap<String, CircuitState> states = new ConcurrentHashMap<>();
    private final int      failureThreshold;
    private final Duration cooldown;
    private final Clock    clock;

    /** Uses the breaker's documented defaults: 3 consecutive failures, 30s cooldown. */
    public CircuitStateRegistry() {
        this(CircuitBreakerLlmClient.DEFAULT_FAILURE_THRESHOLD,
             CircuitBreakerLlmClient.DEFAULT_COOLDOWN, Clock.systemUTC());
    }

    /** Package-visible so tests can freeze the clock and shorten the thresholds. */
    CircuitStateRegistry(int failureThreshold, Duration cooldown, Clock clock) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (cooldown.isZero() || cooldown.isNegative())
            throw new IllegalArgumentException("cooldown must be positive");
        this.failureThreshold = failureThreshold;
        this.cooldown         = cooldown;
        this.clock            = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The health of {@code transportId}, created on first use. Callers wrapping the same endpoint
     * get the same instance, which is the whole point.
     */
    CircuitState stateFor(String transportId) {
        Objects.requireNonNull(transportId, "transportId must not be null");
        return states.computeIfAbsent(transportId,
                id -> new CircuitState(failureThreshold, cooldown, clock));
    }

    /**
     * Wraps {@code delegate} in a breaker over the shared health of {@code transportId}.
     *
     * <p>This method exists so {@link CircuitState} can stay package-private: the wiring needs to
     * build a breaker bound to shared health without being handed the state machine itself, and a
     * single call expresses that better than exposing the state type and the breaker's
     * state-accepting constructor to the whole runtime.
     */
    public LlmClient breakerFor(String transportId, LlmClient delegate, AraTelemetry telemetry) {
        return new CircuitBreakerLlmClient(delegate, stateFor(transportId), telemetry);
    }
}
