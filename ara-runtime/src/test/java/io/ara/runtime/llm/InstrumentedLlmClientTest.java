package io.ara.runtime.llm;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.telemetry.SpanStatus;
import io.ara.runtime.telemetry.RecordingTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies every attribute {@link InstrumentedLlmClient} documents on the {@code llm.complete}
 * span, plus the error path — {@link io.ara.runtime.AraRuntimeTelemetryTest} only checks span
 * count and {@code llm.provider}, so tokens/latency/finish_reason/model resolution and
 * exception handling had no coverage of their own.
 */
class InstrumentedLlmClientTest {

    private static final List<LlmMessage> MESSAGES = List.of(new LlmMessage("user", "hi"));

    private static LlmCallContext ctxFor(String agentType) {
        return LlmCallContext.from(AgentConfig.defaults().agentType(agentType).build());
    }

    @Test
    void complete_recordsAllDocumentedAttributesOnSuccess() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient delegate = new StubLlmClient(
                new LlmCompletion("answer", 12, 34, "stop", null), null);
        LlmClient instrumented = new InstrumentedLlmClient(delegate, telemetry);

        LlmCompletion result = instrumented.complete(MESSAGES, ctxFor("researcher"));

        assertEquals("answer", result.text());
        RecordingTelemetry.RecordedSpan span = telemetry.spansNamed("llm.complete").get(0);
        assertEquals("stub-provider", span.attributes().get("llm.provider"));
        assertEquals("researcher",    span.attributes().get("llm.model"));
        assertEquals(12L,             span.attributes().get("llm.tokens.prompt"));
        assertEquals(34L,             span.attributes().get("llm.tokens.output"));
        assertEquals("stop",          span.attributes().get("llm.finish_reason"));
        assertTrue(span.attributes().containsKey("llm.latency_ms"));
        assertEquals(SpanStatus.OK, span.status());
        assertFalse(span.hasException());
    }

    @Test
    void supportsNativeTools_delegatesToUnderlyingClient() {
        LlmClient nativeDelegate    = new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null, true);
        LlmClient nonNativeDelegate = new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null, false);

        assertTrue(new InstrumentedLlmClient(nativeDelegate, new RecordingTelemetry()).supportsNativeTools());
        assertFalse(new InstrumentedLlmClient(nonNativeDelegate, new RecordingTelemetry()).supportsNativeTools());
    }

    @Test
    void complete_recordsSessionId_whenContextCarriesOne() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient instrumented = new InstrumentedLlmClient(
                new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null), telemetry);

        io.ara.core.agent.AgentTask task = io.ara.core.agent.AgentTask.of("hi")
                .withSessionId(io.ara.core.agent.SessionId.of("sess-42"));
        LlmCallContext ctx = LlmCallContext.of(
                AgentConfig.defaults().agentType("researcher").build(), task);

        instrumented.complete(MESSAGES, ctx);

        assertEquals("sess-42",
                telemetry.spansNamed("llm.complete").get(0).attributes().get("session.id"));
    }

    @Test
    void complete_omitsSessionId_whenTaskHasNone() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient instrumented = new InstrumentedLlmClient(
                new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null), telemetry);

        // ctxFor(...) is built via LlmCallContext.from(config), which has no task at all —
        // sessionId must simply be absent, not a null-valued attribute.
        instrumented.complete(MESSAGES, ctxFor("researcher"));

        assertFalse(telemetry.spansNamed("llm.complete").get(0).attributes().containsKey("session.id"));
    }

    @Test
    void complete_recordsTokensEstimatedFalse_forOrdinaryCompletions() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient instrumented = new InstrumentedLlmClient(
                new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null), telemetry);

        instrumented.complete(MESSAGES, ctxFor("researcher"));

        assertEquals(false,
                telemetry.spansNamed("llm.complete").get(0).attributes().get("llm.tokens_estimated"),
                "llm.tokens_estimated must be present (not merely absent) and false for exact counts");
    }

    @Test
    void complete_recordsTokensEstimatedTrue_whenCompletionSaysSo() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmCompletion estimated = new LlmCompletion(
                "a", 1, 1, "stop", null, null, List.of(), true);
        LlmClient instrumented = new InstrumentedLlmClient(new StubLlmClient(estimated, null), telemetry);

        instrumented.complete(MESSAGES, ctxFor("researcher"));

        assertEquals(true,
                telemetry.spansNamed("llm.complete").get(0).attributes().get("llm.tokens_estimated"),
                "ReactStrategy's streaming fallback marks its estimated token counts — "
                + "this must surface on the span so a cost dashboard doesn't treat it as exact");
    }

    @Test
    void complete_modelResolution_prefersProviderOverrideOverAgentType() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient instrumented = new InstrumentedLlmClient(
                new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null), telemetry);

        LlmCallContext ctx = ctxFor("researcher").withProviderOverride("gpt-4o-mini");
        instrumented.complete(MESSAGES, ctx);

        assertEquals("gpt-4o-mini",
                telemetry.spansNamed("llm.complete").get(0).attributes().get("llm.model"));
    }

    @Test
    void complete_modelResolution_fallsBackToUnknown_whenContextIsNull() {
        // AgentConfig/AgentIdentity guarantee a non-blank agentType, so the only reachable
        // way to hit the "neither is set" branch of resolveModel(...) through the public
        // API is a caller that has no LlmCallContext at all.
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmClient instrumented = new InstrumentedLlmClient(
                new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null), telemetry);

        instrumented.complete(MESSAGES, (LlmCallContext) null);

        assertEquals("unknown",
                telemetry.spansNamed("llm.complete").get(0).attributes().get("llm.model"));
    }

    @Test
    void complete_onLlmException_recordsExceptionAndErrorStatus_thenRethrows() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmException failure = LlmException.rateLimit("stub-provider", "too many requests");
        LlmClient instrumented = new InstrumentedLlmClient(
                new StubLlmClient(null, failure), telemetry);

        LlmException thrown = assertThrows(LlmException.class,
                () -> instrumented.complete(MESSAGES, ctxFor("researcher")));
        assertSame(failure, thrown);

        RecordingTelemetry.RecordedSpan span = telemetry.spansNamed("llm.complete").get(0);
        assertEquals(SpanStatus.ERROR, span.status());
        assertTrue(span.hasException());
        assertTrue(span.attributes().containsKey("llm.latency_ms"),
                "latency must still be recorded on the failure path");
        // No completion was produced — token/finish_reason attributes must be absent
        assertFalse(span.attributes().containsKey("llm.tokens.prompt"));
        assertFalse(span.attributes().containsKey("llm.finish_reason"));
    }

    @Test
    void providerId_and_lastUsedProviderId_delegateThrough() {
        LlmClient delegate = new StubLlmClient(new LlmCompletion("a", 1, 1, "stop", null), null);
        LlmClient instrumented = new InstrumentedLlmClient(delegate, new RecordingTelemetry());

        assertEquals(delegate.providerId(),        instrumented.providerId());
        assertEquals(delegate.lastUsedProviderId(), instrumented.lastUsedProviderId());
    }

    // ── Stub ─────────────────────────────────────────────────────────────────────

    private static final class StubLlmClient implements LlmClient {
        private final LlmCompletion completion;
        private final LlmException  failure;
        private final boolean       nativeTools;

        StubLlmClient(LlmCompletion completion, LlmException failure) {
            this(completion, failure, false);
        }

        StubLlmClient(LlmCompletion completion, LlmException failure, boolean nativeTools) {
            this.completion  = completion;
            this.failure     = failure;
            this.nativeTools = nativeTools;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            if (failure != null) throw failure;
            return completion;
        }

        @Override public String providerId() { return "stub-provider"; }

        @Override public boolean supportsNativeTools() { return nativeTools; }
    }
}
