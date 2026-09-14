package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LlmClient} decorator that adds a per-candidate circuit breaker to ordered failover.
 *
 * <p>It wraps a <em>single</em> client and is meant to be composed underneath
 * {@link FailoverLlmClient}: the wiring for {@code LlmSelectionPolicy.FAILOVER} wraps every
 * candidate in its own breaker, so the pool's ordering, logging and streaming
 * before-first-token failover all keep working unchanged while each candidate gets its own
 * health state. {@link FailoverLlmClient} remains the orchestrator; this class only decides
 * whether <em>its</em> candidate is worth trying on a given call.
 *
 * <h2>State machine</h2>
 * <ul>
 *   <li><b>CLOSED</b> — normal operation: every call reaches the delegate. A
 *       failover-able failure (network, 5xx, rate limit — {@link LlmException#shouldFailover()})
 *       increments a counter; on the configured threshold the circuit opens. A success resets
 *       the counter.</li>
 *   <li><b>OPEN</b> — the candidate is skipped: calls fast-fail with a failover-able
 *       {@link LlmException} before ever touching the delegate, so the enclosing pool advances
 *       to the next candidate without paying this endpoint's connect/read timeout. That is the
 *       whole point: during an outage you wait for the timeout once to open the circuit, not on
 *       every request.</li>
 *   <li><b>HALF_OPEN</b> — after {@code cooldown} has elapsed since the circuit opened, the
 *       <em>next</em> call is allowed through as a single trial. Success closes the circuit;
 *       a failover-able failure reopens it for another cooldown. While a trial is in flight,
 *       concurrent calls fast-fail as if OPEN (only one probe at a time).</li>
 * </ul>
 *
 * <p>A <em>non-failover</em> failure (401, invalid request, content filter) never counts and
 * never opens the circuit: it would recur on every candidate in the pool, so it is rethrown
 * untouched for the pool's abort semantics to decide ({@code FailoverLlmClient.complete}
 * already stops the whole chain on it). Opening the circuit for it would only route a
 * misconfiguration around to another provider that fails identically.
 *
 * <p>Health is derived from real traffic, never from artificial probes: the whole point of a
 * passive breaker is that it costs zero extra API calls (no probe thread hammering the
 * endpoint it is trying to protect) and that the trial travels through the ordinary
 * {@link #complete}/{@link #stream} path, so it observes exactly what a production call
 * observes. A separate watchdog thread that actively pings the candidates was considered and
 * rejected: probes bill like real calls, can themselves trip the rate limit the breaker is
 * meant to absorb, and can mark an endpoint down on a probe-specific time-out that real calls
 * would have survived — the passive model reads the evidence the traffic already produced.
 *
 * <p>Configuration is deliberately fixed to sensible defaults ({@value #DEFAULT_FAILURE_THRESHOLD}
 * consecutive failures, {@value #DEFAULT_COOLDOWN_SECONDS}s cooldown): the values matter far
 * less than the mechanism, and every knob is another contract to test and defend (the wiring
 * in {@code DefaultWiringFactory} applies this with no parameters).
 */
public final class CircuitBreakerLlmClient implements LlmClient {

    /** Consecutive failover-able failures that open the circuit. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;
    /** How long the circuit stays open before a single trial is allowed. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);
    /** Mirror of {@link #DEFAULT_COOLDOWN} as a literal, so the javadoc can render it with {@code {@value}}. */
    static final int DEFAULT_COOLDOWN_SECONDS = 30;

    private final LlmClient            delegate;
    private final int                  failureThreshold;
    private final Duration             cooldown;
    private final Clock                clock;

    private final AtomicReference<State> state          = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger          consecutiveFailures = new AtomicInteger(0);
    /** Epoch millis of the last transition to OPEN; meaningless otherwise. */
    private final AtomicLong            openedAtMillis  = new AtomicLong(-1L);

    public CircuitBreakerLlmClient(LlmClient delegate) {
        this(delegate, DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN, Clock.systemUTC());
    }

    public CircuitBreakerLlmClient(LlmClient delegate, int failureThreshold, Duration cooldown) {
        this(delegate, failureThreshold, cooldown, Clock.systemUTC());
    }

    /** Package-visible so tests can freeze/advance the clock and hold the trial threads. */
    CircuitBreakerLlmClient(LlmClient delegate, int failureThreshold, Duration cooldown, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (cooldown.isZero() || cooldown.isNegative())
            throw new IllegalArgumentException("cooldown must be positive");
        this.failureThreshold = failureThreshold;
        this.cooldown         = cooldown;
        this.clock            = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        allowTrialIfCooldownElapsed();
        try {
            LlmCompletion result = delegate.complete(messages, context);
            onSuccess();
            return result;
        } catch (RuntimeException ex) {
            onFailure(ex);
            throw ex;
        }
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        allowTrialIfCooldownElapsed();
        Flow.Publisher<String> inner = delegate.stream(messages, context);
        return subscriber -> inner.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }
            @Override public void onNext(String item) { subscriber.onNext(item); }
            @Override public void onError(Throwable error) {
                // A mid-stream failure counts exactly like a blocking one: the endpoint proved
                // flaky, and opening the circuit makes the next request skip it. FailoverLlmClient
                // will not re-route this particular stream past the first token, but the next call
                // pays the lesson instead of this one.
                onFailure(error);
                subscriber.onError(error);
            }
            @Override public void onComplete() {
                onSuccess();
                subscriber.onComplete();
            }
        });
    }

    /**
     * CLOSED: proceed. OPEN with cooldown not yet elapsed, or HALF_OPEN with a trial already in
     * flight: throw fast-fail so the enclosing pool advances without paying this candidate's
     * timeout. OPEN with cooldown elapsed: claim the single trial via a CAS and proceed.
     */
    private void allowTrialIfCooldownElapsed() {
        State s = state.get();
        if (s == State.CLOSED) return;
        if (s == State.HALF_OPEN) throw fastFail();
        long now = clock.millis();
        if (now - openedAtMillis.get() < cooldown.toMillis()) throw fastFail();
        if (!state.compareAndSet(State.OPEN, State.HALF_OPEN)) throw fastFail();
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    private void onFailure(Throwable ex) {
        if ((ex instanceof LlmException le) && !le.shouldFailover()) {
            // Deterministic (401 / invalid request / content filter): rethrown untouched so the
            // pool aborts. Counting it toward OPEN would only let a config error monopolise the
            // fallback, which fails the same way.
            return;
        }
        // A half-open trial that failed reopens immediately, however many consecutive failures
        // had accumulated before — one probe, one verdict, another cooldown.
        if (state.get() == State.HALF_OPEN) {
            open();
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            open();
        }
    }

    private void open() {
        consecutiveFailures.set(0);
        state.set(State.OPEN);
        openedAtMillis.set(clock.millis());
    }

    private LlmException fastFail() {
        return LlmException.connectionError(delegate.providerId(),
                "circuit open — skipping '" + delegate.providerId() + "' after "
                        + failureThreshold + " consecutive failures (cooldown " + cooldown + ")",
                null);
    }

    @Override
    public String providerId() {
        return delegate.providerId();
    }

    @Override
    public String lastUsedProviderId() {
        return delegate.lastUsedProviderId();
    }

    @Override
    public boolean supportsNativeTools() {
        // Must forward the delegate's capability: a breaker that inherited the default false
        // would quietly strip native tool-calling whenever FAILOVER wraps a capable client
        // (see LlmClient.supportsNativeTools javadoc).
        return delegate.supportsNativeTools();
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return delegate.supportedMediaTypes();
    }

    /**
     * Lifecycle of a single candidate's breaker.
     * <ul>
     *   <li>CLOSED: normal operation, call the delegate.</li>
     *   <li>OPEN: skip the delegate until the cooldown has elapsed.</li>
     *   <li>HALF_OPEN: a single trial call is in flight (or was just claimed); all others skip.</li>
     * </ul>
     */
    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Exposed for tests (same package) and diagnostics; {@link State#CLOSED} when never tripped. */
    State state() {
        return state.get();
    }

    @Override
    public String toString() {
        return "circuit-breaker[" + delegate.providerId() + ":" + state.get() + "]";
    }
}