package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.telemetry.AraTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the point of {@link CircuitStateRegistry}: circuit health belongs to the endpoint, so
 * breakers wrapping the same {@code transportId} share one verdict while breakers over different
 * ids stay independent.
 *
 * <p>The scenario each test stands in for is a runtime with several sessions: ARA pins a breaker
 * wrapper per session over a transport that all sessions share, so "two breakers over the same
 * transportId" is literally "two sessions talking to one endpoint".
 */
class CircuitStateRegistryTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private static final class FakeClock extends Clock {
        private long millis;
        FakeClock(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
        void advance(Duration d) { millis += d.toMillis(); }
    }

    /** Counts calls and fails with a failover-able 500 until told otherwise. */
    private static final class ScriptedClient implements LlmClient {
        private final String id;
        private final AtomicBoolean fail  = new AtomicBoolean(true);
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedClient(String id) { this.id = id; }

        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.incrementAndGet();
            if (fail.get()) throw LlmException.serverError(id, "simulated 500", 500);
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }
        @Override public String providerId() { return id; }

        int calls()    { return calls.get(); }
        void succeed() { fail.set(false); }
    }

    private static List<LlmMessage> hello() {
        return List.of(LlmMessage.user("hi"));
    }

    private static void failQuietly(LlmClient client) {
        assertThrows(LlmException.class, () -> client.complete(hello(), (LlmCallContext) null));
    }

    @Test
    void breakersOverTheSameTransportShareOneCircuit() {
        var registry = new CircuitStateRegistry(2, COOLDOWN, new FakeClock(0));
        ScriptedClient endpoint = new ScriptedClient("openai-gpt-4o");
        LlmClient sessionA = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());
        LlmClient sessionB = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());

        // Session A alone reaches the threshold of 2 and opens the circuit.
        failQuietly(sessionA);
        failQuietly(sessionA);
        assertEquals(2, endpoint.calls());

        // Session B never failed, yet it must already skip the endpoint: the outage is a fact about
        // the endpoint, which B shares with A.
        LlmException fast = assertThrows(LlmException.class,
                () -> sessionB.complete(hello(), (LlmCallContext) null));
        assertTrue(fast.getMessage().contains("circuit open"));
        assertEquals(2, endpoint.calls(), "session B must not pay the dead endpoint's timeout");
    }

    @Test
    void anOutageIsLearnedOncePerEndpoint_notOncePerSession() {
        // The regression this design fixes: with per-wrapper state, 10 sessions × threshold 3 cost
        // 30 real timeouts to open every circuit, and every new session added 3 more.
        var registry = new CircuitStateRegistry(3, COOLDOWN, new FakeClock(0));
        ScriptedClient endpoint = new ScriptedClient("openai-gpt-4o");

        for (int session = 0; session < 10; session++) {
            LlmClient breaker = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());
            for (int call = 0; call < 3; call++) {
                failQuietly(breaker);
            }
        }

        assertEquals(3, endpoint.calls(),
                "the endpoint is probed up to the threshold once for the whole runtime");
    }

    @Test
    void differentModelsOnTheSameEndpointKeepIndependentCircuits() {
        // Two models behind one host: the deprecated one 404s while the healthy one serves. Keying
        // health by provider id ("openai-" + model) or by base URL would have let the dead model
        // open the circuit of the live one and throw away working capacity.
        var registry = new CircuitStateRegistry(1, COOLDOWN, new FakeClock(0));
        ScriptedClient removedModel = new ScriptedClient("openai-gpt-legacy");
        ScriptedClient healthyModel = new ScriptedClient("openai-gpt-4o");
        healthyModel.succeed();

        LlmClient deadBreaker    = registry.breakerFor("t-legacy", removedModel, AraTelemetry.noop());
        LlmClient healthyBreaker = registry.breakerFor("t-gpt4o",  healthyModel, AraTelemetry.noop());

        failQuietly(deadBreaker);
        failQuietly(deadBreaker);   // now fast-failed, circuit open

        assertEquals("ok", healthyBreaker.complete(hello(), (LlmCallContext) null).text(),
                "a dead sibling model must not open this model's circuit");
        assertEquals(1, healthyModel.calls());
    }

    @Test
    void recoveryThroughOneSessionClosesTheCircuitForAll() {
        FakeClock clock = new FakeClock(0);
        var registry = new CircuitStateRegistry(1, COOLDOWN, clock);
        ScriptedClient endpoint = new ScriptedClient("openai-gpt-4o");
        LlmClient sessionA = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());
        LlmClient sessionB = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());

        failQuietly(sessionA);
        clock.advance(COOLDOWN);
        endpoint.succeed();

        // A's trial recovers the endpoint; B benefits without probing it itself.
        assertEquals("ok", sessionA.complete(hello(), (LlmCallContext) null).text());
        assertEquals("ok", sessionB.complete(hello(), (LlmCallContext) null).text());
    }

    @Test
    void onlyOneSessionGetsTheTrialAfterCooldown() {
        FakeClock clock = new FakeClock(0);
        var registry = new CircuitStateRegistry(1, COOLDOWN, clock);
        ScriptedClient endpoint = new ScriptedClient("openai-gpt-4o");
        LlmClient sessionA = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());
        LlmClient sessionB = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());

        failQuietly(sessionA);
        assertEquals(1, endpoint.calls());
        clock.advance(COOLDOWN);

        // A claims the single trial (and fails it, reopening). B must not probe in the same window.
        failQuietly(sessionA);
        assertEquals(2, endpoint.calls());
        failQuietly(sessionB);
        assertEquals(2, endpoint.calls(), "one probe per cooldown for the endpoint, not per session");
    }

    @Test
    void concurrentFailuresAcrossSessionsStillOpenTheSharedCircuit() throws Exception {
        // What the compound CAS guarantees: no lost update. Under a storm of concurrent failures
        // the counter cannot be corrupted into never reaching the threshold, and the circuit ends
        // up OPEN exactly once for the endpoint.
        //
        // What it deliberately does not guarantee: a hard cap on delegate calls. Many threads can
        // pass the gate while the circuit is still CLOSED and be in flight before the first failure
        // is recorded — a breaker counts recorded failures, it does not limit in-flight
        // concurrency. Capping that is a bulkhead (a semaphore around the call), a different
        // pattern with a different cost, and asserting it here would be asserting a property this
        // class never claimed.
        int threshold = 5;
        var registry = new CircuitStateRegistry(threshold, COOLDOWN, new FakeClock(0));
        ScriptedClient endpoint = new ScriptedClient("openai-gpt-4o");

        int sessions = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(sessions);
        for (int i = 0; i < sessions; i++) {
            LlmClient breaker = registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int call = 0; call < 4; call++) {
                        try {
                            breaker.complete(hello(), (LlmCallContext) null);
                        } catch (LlmException expected) {
                            // either a real 500 or a fast-fail — both are failures to the caller
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "virtual threads must finish");

        var observer = (CircuitBreakerLlmClient) registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop());
        assertEquals(CircuitBreakerLlmClient.State.OPEN, observer.state(),
                "concurrent failures must still open the shared circuit");

        // And once open it stays shut to everyone: no further call reaches the endpoint.
        int callsWhenOpen = endpoint.calls();
        failQuietly(observer);
        failQuietly(registry.breakerFor("t-gpt4o", endpoint, AraTelemetry.noop()));
        assertEquals(callsWhenOpen, endpoint.calls(),
                "an open shared circuit fast-fails every session without touching the endpoint");
    }

    @Test
    void standaloneBreakersStillKeepTheirOwnState() {
        // The public constructors are unchanged: a breaker built directly owns its health, which is
        // what every existing direct caller (and FailoverLlmClient's own tests) relies on.
        ScriptedClient endpoint = new ScriptedClient("openai-gpt-4o");
        var first  = new CircuitBreakerLlmClient(endpoint, 1, COOLDOWN);
        var second = new CircuitBreakerLlmClient(endpoint, 1, COOLDOWN);

        failQuietly(first);
        assertEquals(CircuitBreakerLlmClient.State.OPEN, first.state());
        assertEquals(CircuitBreakerLlmClient.State.CLOSED, second.state(),
                "an independently built breaker is not affected by another's verdict");
    }

    @Test
    void registryRejectsDegenerateConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitStateRegistry(0, COOLDOWN, new FakeClock(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitStateRegistry(2, Duration.ZERO, new FakeClock(0)));
        assertThrows(NullPointerException.class,
                () -> new CircuitStateRegistry(2, COOLDOWN, null));
        var registry = new CircuitStateRegistry(2, COOLDOWN, new FakeClock(0));
        assertThrows(NullPointerException.class, () -> registry.stateFor(null));
    }
}
