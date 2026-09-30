package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.telemetry.SpanStatus;
import io.ara.runtime.telemetry.RecordingTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link FailoverLlmClient#supportsNativeTools()}'s all-match semantics, the
 * before-first-token failover contract of {@link FailoverLlmClient#stream}, and the
 * {@code llm.failover} spans both paths record.
 */
class FailoverLlmClientTest {

    private static LlmClient client(boolean nativeTools) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("ok", 1, 1, "stop", null);
            }
            @Override public String providerId() { return nativeTools ? "native" : "non-native"; }
            @Override public boolean supportsNativeTools() { return nativeTools; }
        };
    }

    @Test
    void supportsNativeTools_trueOnlyWhenEveryCandidateSupportsIt() {
        assertTrue(new FailoverLlmClient(List.of(client(true), client(true))).supportsNativeTools());
        assertFalse(new FailoverLlmClient(List.of(client(true), client(false))).supportsNativeTools());
        assertFalse(new FailoverLlmClient(List.of(client(false))).supportsNativeTools());
    }

    // ── streaming ─────────────────────────────────────────────────────────────

    @Test
    void stream_forwardsPrimaryTokens_whenPrimarySucceeds() {
        LlmClient primary   = streamingClient("p", stream -> { stream.token("Hel"); stream.token("lo"); stream.complete(); });
        LlmClient secondary = streamingClient("s", stream -> fail("secondary must not be used"));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertEquals("Hello", c.text());
        assertTrue(c.completed());
        assertNull(c.error());
    }

    @Test
    void stream_failsOverToNextClient_whenPrimaryErrorsBeforeAnyToken() {
        LlmClient primary   = streamingClient("p", stream -> stream.error(LlmException.rateLimit("p", "429")));
        LlmClient secondary = streamingClient("s", stream -> { stream.token("from-fallback"); stream.complete(); });

        FailoverLlmClient failover = new FailoverLlmClient(List.of(primary, secondary));
        Collected c = collect(failover);

        assertEquals("from-fallback", c.text());
        assertTrue(c.completed());
        assertNull(c.error());
        assertEquals("s", failover.lastUsedProviderId());
    }

    @Test
    void stream_doesNotFailOver_afterFirstTokenDelivered() {
        LlmClient primary = streamingClient("p", stream -> {
            stream.token("partial");
            stream.error(LlmException.serverError("p", "boom", 503));
        });
        LlmClient secondary = streamingClient("s", stream -> fail("must not fail over once a token was delivered"));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertEquals("partial", c.text());
        assertFalse(c.completed());
        assertNotNull(c.error());
    }

    @Test
    void stream_abortsImmediately_onNonRetryableError() {
        LlmClient primary   = streamingClient("p", stream -> stream.error(LlmException.authenticationError("p", "401")));
        LlmClient secondary = streamingClient("s", stream -> fail("non-retryable error must abort failover"));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertEquals("", c.text());
        assertFalse(c.completed());
        assertInstanceOf(LlmException.class, c.error());
    }

    @Test
    void stream_propagatesError_whenEveryCandidateFailsBeforeTokens() {
        LlmClient primary   = streamingClient("p", stream -> stream.error(LlmException.networkError("p", "down", null)));
        LlmClient secondary = streamingClient("s", stream -> stream.error(LlmException.networkError("s", "down", null)));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertFalse(c.completed());
        assertNotNull(c.error());
    }

    // ── telemetry ──────────────────────────────────────────────────────────────

    /** A client whose complete() fails failover-ably when {@code failing} is true. */
    private static LlmClient scripted(String id, boolean failing) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                if (failing) throw LlmException.rateLimit(id, "429");
                return new LlmCompletion("ok-" + id, 1, 1, "stop", null);
            }
            @Override public String providerId() { return id; }
        };
    }

    private static LlmCompletion call(LlmClient client) {
        return client.complete(List.of(LlmMessage.user("hi")), (LlmCallContext) null);
    }

    @Test
    void complete_recordsServedByFallback_afterThePrimaryFailed() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        FailoverLlmClient pool = new FailoverLlmClient(List.of(scripted("p", true), scripted("s", false)), telemetry);

        call(pool);

        var span = telemetry.spansNamed("llm.failover").get(0);
        assertEquals("served_by_fallback", span.attributes().get("llm.failover.outcome"));
        assertEquals(2L, span.attributes().get("llm.failover.attempts"));
        assertEquals("s", span.attributes().get("llm.failover.served_by"));
        assertEquals("failover[p→s]", span.attributes().get("llm.failover.chain"));
        assertEquals(SpanStatus.OK, span.status());
        assertFalse(span.hasException());
    }

    @Test
    void complete_recordsServedByPrimary_withoutAnyFailure() {
        RecordingTelemetry telemetry = new RecordingTelemetry();

        call(new FailoverLlmClient(List.of(scripted("p", false), scripted("s", false)), telemetry));

        var span = telemetry.spansNamed("llm.failover").get(0);
        assertEquals("served_by_primary", span.attributes().get("llm.failover.outcome"));
        assertEquals(1L, span.attributes().get("llm.failover.attempts"));
        assertEquals("p", span.attributes().get("llm.failover.served_by"));
        assertEquals(SpanStatus.OK, span.status());
    }

    @Test
    void complete_recordsTheAbort_onANonFailoverError() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient failing = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw LlmException.authenticationError("p", "invalid api key");
            }
            @Override public String providerId() { return "p"; }
        };

        FailoverLlmClient pool = new FailoverLlmClient(List.of(failing, scripted("s", false)), telemetry);
        assertThrows(LlmException.class, () -> call(pool));

        var span = telemetry.spansNamed("llm.failover").get(0);
        assertEquals("aborted_non_failover", span.attributes().get("llm.failover.outcome"));
        assertEquals(1L, span.attributes().get("llm.failover.attempts"));
        assertEquals(SpanStatus.ERROR, span.status());
        assertTrue(span.hasException(), "the aborting LlmException must be recorded on the span");
    }

    @Test
    void complete_recordsExhaustion_whenEveryCandidateFailed() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        FailoverLlmClient pool = new FailoverLlmClient(List.of(scripted("p", true), scripted("s", true)), telemetry);

        assertThrows(LlmException.class, () -> call(pool));

        var span = telemetry.spansNamed("llm.failover").get(0);
        assertEquals("exhausted", span.attributes().get("llm.failover.outcome"));
        assertEquals(2L, span.attributes().get("llm.failover.attempts"));
        assertEquals(SpanStatus.ERROR, span.status());
        assertTrue(span.hasException());
    }

    @Test
    void complete_recordsTheInterrupt_whenTheCallingThreadWasInterrupted() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        FailoverLlmClient pool = new FailoverLlmClient(
                List.of(scripted("p", true), scripted("s", false)), telemetry);

        Thread.currentThread().interrupt();
        try {
            assertThrows(LlmException.class, () -> call(pool));
        } finally {
            Thread.interrupted();
        }

        var span = telemetry.spansNamed("llm.failover").get(0);
        assertEquals("interrupted", span.attributes().get("llm.failover.outcome"));
        assertEquals(1L, span.attributes().get("llm.failover.attempts"));
        assertEquals(SpanStatus.ERROR, span.status());
    }

    @Test
    void stream_recordsOneSpanPerDecision_insteadOfWrappingTheWholeStream() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient primary = streamingClient("p", stream -> stream.error(LlmException.rateLimit("p", "429")));
        LlmClient fallback = streamingClient("s", stream -> { stream.token("hi"); stream.complete(); });

        Collected collected = collect(new FailoverLlmClient(List.of(primary, fallback), telemetry));

        assertEquals("hi", collected.text());
        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("llm.failover");
        assertEquals(2, spans.size(), "one span for the switch, one for the candidate that answered");

        var switched = spans.get(0);
        assertEquals("switching", switched.attributes().get("llm.failover.outcome"));
        assertEquals("p", switched.attributes().get("llm.failover.provider"));
        assertEquals(1L, switched.attributes().get("llm.failover.attempts"));
        assertEquals(true, switched.attributes().get("llm.failover.streaming"));
        assertEquals(SpanStatus.ERROR, switched.status());

        var served = spans.get(1);
        assertEquals("served", served.attributes().get("llm.failover.outcome"));
        assertEquals("s", served.attributes().get("llm.failover.provider"));
        assertEquals(2L, served.attributes().get("llm.failover.attempts"));
        assertEquals(SpanStatus.OK, served.status());
    }

    @Test
    void stream_recordsTheFailureAfterFirstToken_asNonRetryable() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient primary = streamingClient("p", stream -> {
            stream.token("partial");
            stream.error(LlmException.serverError("p", "boom", 503));
        });
        LlmClient fallback = streamingClient("s", stream -> fail("must not fail over once a token was delivered"));

        collect(new FailoverLlmClient(List.of(primary, fallback), telemetry));

        var span = telemetry.spansNamed("llm.failover").get(0);
        assertEquals("failed_after_first_token", span.attributes().get("llm.failover.outcome"));
        assertEquals(SpanStatus.ERROR, span.status());
    }

    @Test
    void telemetry_isOptionalAndTheNoopDefaultKeepsTheCallWorking() {
        assertEquals("ok-p", call(new FailoverLlmClient(List.of(scripted("p", false)))).text());
        assertThrows(NullPointerException.class,
                () -> new FailoverLlmClient(List.of(scripted("p", false)), null));
    }

    // ── failover on connection errors (non-retryable but failover-able) ─────────

    @Test
    void complete_failsOverToNextClient_onConnectionError() {
        LlmClient primary = new LlmClient() {
            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw LlmException.connectionError("openai", "HTTP connect timed out", null);
            }
            @Override public String providerId() { return "primary"; }
        };

        FailoverLlmClient failover = new FailoverLlmClient(List.of(primary, client(true)));
        LlmCompletion result = failover.complete(List.of(LlmMessage.user("hi")), (LlmCallContext) null);

        assertEquals("ok", result.text());
        assertEquals("native", failover.lastUsedProviderId());
    }

    @Test
    void stream_failsOverToNextClient_onConnectionError() {
        LlmClient primary   = streamingClient("p",
                stream -> stream.error(LlmException.connectionError("openai", "HTTP connect timed out", null)));
        LlmClient secondary = streamingClient("s", stream -> { stream.token("from-fallback"); stream.complete(); });

        FailoverLlmClient failover = new FailoverLlmClient(List.of(primary, secondary));
        Collected c = collect(failover);

        assertEquals("from-fallback", c.text());
        assertTrue(c.completed());
        assertNull(c.error());
        assertEquals("s", failover.lastUsedProviderId());
    }

    @Test
    void complete_abortsImmediately_onNonFailoverError() {
        LlmClient primary = new LlmClient() {
            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw LlmException.authenticationError("openai", "invalid api key");
            }
            @Override public String providerId() { return "primary"; }
        };

        LlmClient secondary = client(true);
        FailoverLlmClient failover = new FailoverLlmClient(List.of(primary, secondary));

        LlmException ex = assertThrows(LlmException.class,
                () -> failover.complete(List.of(LlmMessage.user("hi")), (LlmCallContext) null));
        assertEquals("AUTHENTICATION", ex.errorType().name());
        assertEquals("openai", ex.provider());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Drives a scripted, synchronous token stream toward a subscriber. */
    private interface StreamScript { void run(Emitter emitter); }

    private interface Emitter {
        void token(String t);
        void complete();
        void error(Throwable t);
    }

    private static LlmClient streamingClient(String id, StreamScript script) {
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
                    script.run(emitter);
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
