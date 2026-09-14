package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link CircuitBreakerLlmClient}'s closed/open/half-open state machine, the
 * "never pay a dead endpoint's timeout twice" contract, and the stream delegation.
 *
 * <p>Tests exercise the behaviour through the delegate (invocation counts, thrown/fast-fail
 * errors) rather than poking at internals, mirroring {@link FailoverLlmClientTest}'s style:
 * anonymous single-purpose stub clients and a few scripted-emitter helpers.
 */
class CircuitBreakerLlmClientTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    /** A scripted client that either throws a failover-able 500 or completes successfully. */
    private static final class ScriptedClient implements LlmClient {
        private final String id;
        private final AtomicBoolean fail = new AtomicBoolean(true);
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedClient(String id) { this.id = id; }

        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.incrementAndGet();
            if (fail.get()) throw LlmException.serverError(id, "simulated 500", 500);
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }

        @Override public String providerId() { return id; }

        int calls() { return calls.get(); }
        void succeed() { fail.set(false); }
        void failing() { fail.set(true); }
    }

    private static final class FakeClock extends Clock {
        private long millis;
        FakeClock(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
        void advance(Duration d) { millis += d.toMillis(); }
    }

    private static List<LlmMessage> hello() {
        return List.of(LlmMessage.user("hi"));
    }

    // ── closed circuit / failure accounting ─────────────────────────────────

    @Test
    void closedCircuit_forwardsToDelegate_andSucceeds() {
        ScriptedClient delegate = new ScriptedClient("primary");
        delegate.succeed();
        var cb = new CircuitBreakerLlmClient(delegate, 2, COOLDOWN, new FakeClock(0));

        assertEquals("ok", cb.complete(hello(), (LlmCallContext) null).text());
        assertEquals(1, delegate.calls());
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state());
    }

    @Test
    void opensAfterThreshold_andFastFailsWithoutTouchingDelegate() {
        ScriptedClient delegate = new ScriptedClient("primary");
        var cb = new CircuitBreakerLlmClient(delegate, 2, COOLDOWN, new FakeClock(0));

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state());

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state());
        assertEquals(2, delegate.calls());

        LlmException fast = assertThrows(LlmException.class,
                () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(2, delegate.calls());
        assertEquals("primary", fast.provider());
        assertTrue(fast.shouldFailover(), "fast-fail must be failover-able so the pool advances");
    }

    @Test
    void consecutiveFailuresAreResetByASuccess() {
        ScriptedClient delegate = new ScriptedClient("primary");
        var cb = new CircuitBreakerLlmClient(delegate, 2, COOLDOWN, new FakeClock(0));

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        delegate.succeed();
        assertEquals("ok", cb.complete(hello(), (LlmCallContext) null).text());
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state());
        delegate.failing();

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state());
    }

    @Test
    void nonFailoverError_isRethrown_andNeverOpensTheCircuit() {
        LlmClient delegate = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw LlmException.authenticationError("primary", "invalid api key");
            }
            @Override public String providerId() { return "primary"; }
        };
        var cb = new CircuitBreakerLlmClient(delegate, 1, COOLDOWN, new FakeClock(0));

        for (int i = 0; i < 2; i++) {
            LlmException ex = assertThrows(LlmException.class,
                    () -> cb.complete(hello(), (LlmCallContext) null));
            assertEquals("AUTHENTICATION", ex.errorType().name());
        }
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state(),
                "a deterministic error must never open the circuit");
    }

    @Test
    void genericRuntimeException_countsAsAFailure() {
        LlmClient delegate = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw new IllegalStateException("exploded locally");
            }
            @Override public String providerId() { return "primary"; }
        };
        var cb = new CircuitBreakerLlmClient(delegate, 1, COOLDOWN, new FakeClock(0));

        assertThrows(IllegalStateException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state());

        LlmException fast = assertThrows(LlmException.class,
                () -> cb.complete(hello(), (LlmCallContext) null));
        assertTrue(fast.shouldFailover());
    }

    // ── cooldown / half-open trials ─────────────────────────────────────────

    @Test
    void staysOpen_untilCooldownElapses() {
        FakeClock clock = new FakeClock(0);
        ScriptedClient delegate = new ScriptedClient("primary");
        var cb = new CircuitBreakerLlmClient(delegate, 1, COOLDOWN, clock);

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state());

        clock.advance(Duration.ofSeconds(29));
        LlmException fast = assertThrows(LlmException.class,
                () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(1, delegate.calls(), "a second call before the cooldown must not reach the delegate");
        assertTrue(fast.shouldFailover(), "a cooldown-enforced skip must still hint the pool to fail over");
    }

    @Test
    void cooldownTrial_closesCircuitOnSuccess() {
        FakeClock clock = new FakeClock(0);
        ScriptedClient delegate = new ScriptedClient("primary");
        var cb = new CircuitBreakerLlmClient(delegate, 1, COOLDOWN, clock);

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        clock.advance(Duration.ofSeconds(30));
        delegate.succeed();

        assertEquals("ok", cb.complete(hello(), (LlmCallContext) null).text());
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state());
        assertEquals("ok", cb.complete(hello(), (LlmCallContext) null).text());
        assertEquals(3, delegate.calls(), "recovered endpoint serves every subsequent call again");
    }

    @Test
    void failedTrial_reopensTheCircuit_forAnotherCooldown() {
        FakeClock clock = new FakeClock(0);
        ScriptedClient delegate = new ScriptedClient("primary");
        var cb = new CircuitBreakerLlmClient(delegate, 1, COOLDOWN, clock);

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        clock.advance(Duration.ofSeconds(30));

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state(), "a failed trial reopens immediately");

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(2, delegate.calls(), "the opening call plus the failed trial reached the delegate; the reopen is not re-probed");
    }

    @Test
    void halfOpen_allowsExactlyOneTrial_inFlightOthersFastFail() throws Exception {
        FakeClock clock = new FakeClock(0);
        CountDownLatch trialEntered = new CountDownLatch(1);
        CountDownLatch releaseTrial = new CountDownLatch(1);

        LlmClient delegate = new LlmClient() {
            private AtomicBoolean failed = new AtomicBoolean(true);
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                if (failed.getAndSet(false)) throw LlmException.serverError("primary", "simulated 500", 500);
                trialEntered.countDown();
                try {
                    releaseTrial.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return new LlmCompletion("ok", 1, 1, "stop", null);
            }
            @Override public String providerId() { return "primary"; }
        };
        var cb = new CircuitBreakerLlmClient(delegate, 1, COOLDOWN, clock);

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        clock.advance(Duration.ofSeconds(30));

        Thread trial = new Thread(() -> cb.complete(hello(), (LlmCallContext) null));
        trial.start();
        assertTrue(trialEntered.await(5, TimeUnit.SECONDS), "first call after cooldown must become a trial");

        LlmException fast = assertThrows(LlmException.class,
                () -> cb.complete(hello(), (LlmCallContext) null));
        assertTrue(fast.shouldFailover());
        assertEquals("circuit open — skipping 'primary'", fast.getLocalizedMessage().split(" after ")[0]);

        releaseTrial.countDown();
        trial.join(TimeUnit.SECONDS.toMillis(5));
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state(),
                "the in-flight trial succeeded, so the circuit closes");
    }

    // ── streaming ───────────────────────────────────────────────────────────

    @Test
    void stream_whenOpen_throwsFastFailWithoutInvokingDelegate() {
        ScriptedClient delegate = new ScriptedClient("primary");
        var cb = new CircuitBreakerLlmClient(delegate, 1, COOLDOWN, new FakeClock(0));

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));

        LlmException fast = assertThrows(LlmException.class,
                () -> cb.stream(hello(), (LlmCallContext) null));
        assertTrue(fast.shouldFailover());
        assertEquals(1, delegate.calls(), "the open stream must never reach the delegate");
    }

    @Test
    void stream_delegatesTokens_andCompletionClosesTheCircuit() {
        AtomicReference<StreamScript> script = new AtomicReference<>(stream -> {
            stream.token("Hel");
            stream.token("lo");
            stream.complete();
        });
        CircuitBreakerLlmClient streaming =
                new CircuitBreakerLlmClient(streamingClient("primary", script), 1, COOLDOWN, new FakeClock(0));

        Collected c = collect(streaming);
        assertEquals("Hello", c.text());
        assertTrue(c.completed());
        assertNull(c.error());
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, streaming.state());
    }

    @Test
    void stream_failureBeforeTokens_countsAndOpensTheCircuit() {
        AtomicReference<StreamScript> script = new AtomicReference<>(stream ->
                stream.error(LlmException.serverError("primary", "stream 500", 500)));
        var cb = new CircuitBreakerLlmClient(streamingClient("primary", script), 1, COOLDOWN, new FakeClock(0));

        Collected c = collect(cb);
        assertFalse(c.completed());
        assertNotNull(c.error());
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state());

        LlmException fast = assertThrows(LlmException.class,
                () -> cb.complete(hello(), (LlmCallContext) null));
        assertTrue(fast.shouldFailover());
    }

    @Test
    void stream_trial_successAfterCooldown_closesCircuit() {
        FakeClock clock = new FakeClock(0);
        AtomicReference<StreamScript> failing = new AtomicReference<>(stream ->
                stream.error(LlmException.serverError("primary", "stream 500", 500)));
        var cb = new CircuitBreakerLlmClient(streamingClient("primary", failing), 1, COOLDOWN, clock);

        collect(cb);
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state());
        clock.advance(Duration.ofSeconds(30));

        failing.set(stream -> { stream.token("recovered"); stream.complete(); });
        Collected c = collect(cb);
        assertEquals("recovered", c.text());
        assertTrue(c.completed());
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state());
    }

    // ── forwarding ──────────────────────────────────────────────────────────

    @Test
    void forwardsCapabilitiesToDelegate() {
        LlmClient delegate = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("ok", 1, 1, "stop", null);
            }
            @Override public String providerId() { return "native"; }
            @Override public boolean supportsNativeTools() { return true; }
            @Override public Set<String> supportedMediaTypes() { return Set.of("image/png"); }
        };
        var cb = new CircuitBreakerLlmClient(delegate);

        assertEquals("native", cb.providerId());
        assertTrue(cb.supportsNativeTools());
        assertEquals(Set.of("image/png"), cb.supportedMediaTypes());
        assertEquals("native", cb.lastUsedProviderId());
    }

    // ── composition: the point of the breaker ───────────────────────────────

    @Test
    void composedWithFailoverLlmClient_anOpenPrimaryIsSkippedOnEveryCall() {
        ScriptedClient primary = new ScriptedClient("primary");
        LlmClient fallback = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("fallback-ok", 1, 1, "stop", null);
            }
            @Override public String providerId() { return "fallback"; }
        };
        FailoverLlmClient pool = new FailoverLlmClient(List.of(
                new CircuitBreakerLlmClient(primary),
                new CircuitBreakerLlmClient(fallback)));

        for (int i = 0; i < 3; i++) {
            assertEquals("fallback-ok", pool.complete(hello(), (LlmCallContext) null).text());
        }
        assertEquals(3, primary.calls(), "three open-circuiting failures, one per call");

        for (int i = 0; i < 2; i++) {
            assertEquals("fallback-ok", pool.complete(hello(), (LlmCallContext) null).text());
        }
        assertEquals(3, primary.calls(),
                "once open, subsequent calls must skip the primary entirely — no per-request timeout");
        assertEquals("fallback", pool.lastUsedProviderId());
    }

    // ── robustness / defaults ───────────────────────────────────────────────

    @Test
    void defaultConstructor_opensAfterThreeFailures() {
        ScriptedClient delegate = new ScriptedClient("primary");
        var cb = new CircuitBreakerLlmClient(delegate);

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, cb.state());
        assertEquals(3, CircuitBreakerLlmClient.DEFAULT_FAILURE_THRESHOLD);

        assertThrows(LlmException.class, () -> cb.complete(hello(), (LlmCallContext) null));
        assertEquals(CircuitBreakerLlmClient.State.OPEN, cb.state());
    }

    @Test
    void constructor_rejectsDegenerateArguments() {
        LlmClient delegate = new ScriptedClient("primary");
        assertThrows(NullPointerException.class, () -> new CircuitBreakerLlmClient(null));
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreakerLlmClient(delegate, 0, COOLDOWN));
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreakerLlmClient(delegate, 2, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreakerLlmClient(delegate, 2, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class,
                () -> new CircuitBreakerLlmClient(delegate, 2, null));
    }

    // ── helpers (mirror FailoverLlmClientTest) ──────────────────────────────

    private interface StreamScript { void run(Emitter emitter); }

    private interface Emitter {
        void token(String t);
        void complete();
        void error(Throwable t);
    }

    private static LlmClient streamingClient(String id, AtomicReference<StreamScript> script) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("blocking-" + id, 1, 1, "stop", null);
            }
            @Override public String providerId() { return id; }
            @Override public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
                return subscriber -> {
                    AtomicBoolean done = new AtomicBoolean(false);
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) { }
                        @Override public void cancel() { done.set(true); }
                    });
                    Emitter emitter = new Emitter() {
                        @Override public void token(String t) { if (!done.get()) subscriber.onNext(t); }
                        @Override public void complete() { if (done.compareAndSet(false, true)) subscriber.onComplete(); }
                        @Override public void error(Throwable t) { if (done.compareAndSet(false, true)) subscriber.onError(t); }
                    };
                    script.get().run(emitter);
                };
            }
        };
    }

    private record Collected(String text, boolean completed, Throwable error) { }

    private static Collected collect(LlmClient client) {
        StringBuilder buf = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> error = new AtomicReference<>();

        client.stream(List.of(LlmMessage.user("hi")), (LlmCallContext) null).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(String item) { buf.append(item); }
            @Override public void onError(Throwable t) { error.set(t); }
            @Override public void onComplete() { completed.set(true); }
        });

        return new Collected(buf.toString(), completed.get(), error.get());
    }
}