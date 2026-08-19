package io.ara.runtime.llm;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanBuilder;
import io.ara.core.telemetry.SpanStatus;

import java.util.List;
import java.util.Objects;

/**
 * {@link LlmClient} decorator that records OpenTelemetry spans for every
 * {@link #complete} call.
 *
 * <p>Emits a {@code llm.complete} span with the following attributes:
 * <ul>
 *   <li>{@code llm.provider} — {@link LlmClient#providerId()}</li>
 *   <li>{@code llm.model} — model from {@link LlmCallContext}</li>
 *   <li>{@code llm.tokens.prompt} — {@link LlmCompletion#promptTokens()}</li>
 *   <li>{@code llm.tokens.output} — {@link LlmCompletion#outputTokens()}</li>
 *   <li>{@code llm.tokens_estimated} — {@link LlmCompletion#tokensEstimated()}; {@code true}
 *       for the streaming fallback in {@code ReactStrategy}, which has no real provider
 *       usage data. Always set (not conditional) so a cost dashboard built off this span
 *       can't silently mistake an estimate for an exact count.</li>
 *   <li>{@code llm.latency_ms} — wall-clock duration of the call</li>
 *   <li>{@code llm.finish_reason} — {@link LlmCompletion#finishReason()}</li>
 * </ul>
 *
 * <p>When {@link AraTelemetry#noop()} is used, this decorator adds no overhead
 * beyond a single interface dispatch.
 *
 * <p>Usage:
 * <pre>{@code
 * LlmClient instrumented = new InstrumentedLlmClient(delegate, telemetry);
 * }</pre>
 */
public final class InstrumentedLlmClient extends DelegatingLlmClient {

    private final AraTelemetry telemetry;

    public InstrumentedLlmClient(LlmClient delegate, AraTelemetry telemetry) {
        super(delegate);
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        SpanBuilder spanBuilder = telemetry.spanBuilder("llm.complete")
                .setAttribute("llm.provider", delegate.providerId())
                .setAttribute("llm.model",    resolveModel(context));
        // Only set when the originating task carried a session (see LlmCallContext.of) —
        // ephemeral tasks legitimately have none, and context itself may be null (some
        // deprecated call sites still pass no context at all).
        if (context != null && context.hasSessionId()) {
            spanBuilder.setAttribute("session.id", context.sessionId());
        }
        Span span = spanBuilder.startSpan();

        long startMs = System.currentTimeMillis();
        try (var scope = span.makeCurrent()) {
            LlmCompletion completion = delegate.complete(messages, context);
            span.setAttribute("llm.latency_ms",    System.currentTimeMillis() - startMs)
                .setAttribute("llm.tokens.prompt", (long) completion.promptTokens())
                .setAttribute("llm.tokens.output", (long) completion.outputTokens())
                .setAttribute("llm.tokens_estimated", completion.tokensEstimated())
                .setAttribute("llm.finish_reason", completion.finishReason())
                .setStatus(SpanStatus.OK);
            return completion;
        } catch (LlmException e) {
            span.recordException(e)
                .setAttribute("llm.latency_ms", System.currentTimeMillis() - startMs)
                .setStatus(SpanStatus.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    private String resolveModel(LlmCallContext context) {
        if (context == null) return "unknown";
        if (context.llmProviderOverride() != null && !context.llmProviderOverride().isBlank()) {
            return context.llmProviderOverride();
        }
        if (context.agentType() != null && !context.agentType().isBlank()) {
            return context.agentType();
        }
        return "unknown";
    }
}
