package io.ara.adapters.telemetry;

import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanBuilder;
import io.ara.core.telemetry.SpanScope;
import io.ara.core.telemetry.SpanStatus;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.util.Objects;

/**
 * {@link AraTelemetry} implementation backed by the OpenTelemetry SDK.
 *
 * <p>Each call to {@link #spanBuilder(String)} captures the current OTel
 * {@link Context} so spans are automatically children of the ambient parent span.
 * Parent-child relationships are preserved across virtual-thread boundaries as long
 * as the caller holds the context correctly (via {@code try-with-resources} on
 * {@link Span#makeCurrent()}).
 *
 * <p>Use {@link OtelTelemetryFactory} to construct instances:
 * <pre>{@code
 * AraTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 *
 * LlmClient instrumented = new InstrumentedLlmClient(delegate, telemetry);
 * }</pre>
 */
public final class OtelAraTelemetry implements AraTelemetry, AutoCloseable {

    static final String INSTRUMENTATION_NAME = "io.ara";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public OtelAraTelemetry(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    public void close() {
        if (openTelemetry instanceof OpenTelemetrySdk sdk) {
            sdk.close();
        }
    }

    @Override
    public SpanBuilder spanBuilder(String operationName) {
        Objects.requireNonNull(operationName, "operationName must not be null");
        Context parentContext = Context.current();
        return new OtelSpanBuilder(tracer.spanBuilder(operationName).setParent(parentContext));
    }

    @Override
    public Runnable propagate(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        return Context.current().wrap(task);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class OtelSpanBuilder implements SpanBuilder {

        private final io.opentelemetry.api.trace.SpanBuilder delegate;

        OtelSpanBuilder(io.opentelemetry.api.trace.SpanBuilder delegate) {
            this.delegate = delegate;
        }

        @Override public SpanBuilder setAttribute(String key, String value) {
            delegate.setAttribute(key, value != null ? value : ""); return this;
        }
        @Override public SpanBuilder setAttribute(String key, long value) {
            delegate.setAttribute(key, value); return this;
        }
        @Override public SpanBuilder setAttribute(String key, boolean value) {
            delegate.setAttribute(key, value); return this;
        }
        @Override public Span startSpan() {
            return new OtelSpan(delegate.startSpan());
        }
    }

    static final class OtelSpan implements Span {

        private final io.opentelemetry.api.trace.Span delegate;

        OtelSpan(io.opentelemetry.api.trace.Span delegate) {
            this.delegate = delegate;
        }

        @Override public Span setAttribute(String key, String value) {
            delegate.setAttribute(key, value != null ? value : ""); return this;
        }
        @Override public Span setAttribute(String key, long value) {
            delegate.setAttribute(key, value); return this;
        }
        @Override public Span setAttribute(String key, boolean value) {
            delegate.setAttribute(key, value); return this;
        }
        @Override public Span setAttribute(String key, double value) {
            delegate.setAttribute(key, value); return this;
        }
        @Override public Span recordException(Throwable t) {
            if (t != null) delegate.recordException(t); return this;
        }
        @Override public Span setStatus(SpanStatus status) {
            if (status != null) delegate.setStatus(toOtelStatus(status)); return this;
        }
        @Override public SpanScope makeCurrent() {
            var scope = delegate.makeCurrent();
            return scope::close;
        }
        @Override public void end() { delegate.end(); }

        private static StatusCode toOtelStatus(SpanStatus status) {
            return switch (status) {
                case OK    -> StatusCode.OK;
                case ERROR -> StatusCode.ERROR;
                case UNSET -> StatusCode.UNSET;
            };
        }
    }
}
