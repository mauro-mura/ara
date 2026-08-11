package io.ara.runtime.telemetry;

import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanBuilder;
import io.ara.core.telemetry.SpanScope;
import io.ara.core.telemetry.SpanStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double that records every span emitted — name, attributes, status, and whether an
 * exception was recorded — instead of exporting anywhere. Used to assert on the span tree
 * produced by {@code agent.execute} / {@code tool.execute} / {@code llm.complete}.
 */
public final class RecordingTelemetry implements AraTelemetry {

    /** An immutable record of one completed span. */
    public record RecordedSpan(String name, Map<String, Object> attributes, SpanStatus status, boolean hasException) {}

    private final List<RecordedSpan> spans = new CopyOnWriteArrayList<>();

    public List<RecordedSpan> spans() { return List.copyOf(spans); }

    public List<RecordedSpan> spansNamed(String name) {
        return spans.stream().filter(s -> s.name().equals(name)).toList();
    }

    @Override
    public SpanBuilder spanBuilder(String operationName) {
        return new RecordingSpanBuilder(operationName);
    }

    private final class RecordingSpanBuilder implements SpanBuilder {
        private final String name;
        private final Map<String, Object> attrs = new LinkedHashMap<>();

        RecordingSpanBuilder(String name) { this.name = name; }

        @Override public SpanBuilder setAttribute(String key, String value) { attrs.put(key, value); return this; }
        @Override public SpanBuilder setAttribute(String key, long value)   { attrs.put(key, value); return this; }
        @Override public SpanBuilder setAttribute(String key, boolean value){ attrs.put(key, value); return this; }

        @Override
        public Span startSpan() {
            return new RecordingSpan(name, attrs);
        }
    }

    private final class RecordingSpan implements Span {
        private final String name;
        private final Map<String, Object> attrs;
        private SpanStatus status = SpanStatus.UNSET;
        private boolean hasException = false;

        RecordingSpan(String name, Map<String, Object> attrs) {
            this.name  = name;
            this.attrs = attrs;
        }

        @Override public Span setAttribute(String key, String value)  { attrs.put(key, value); return this; }
        @Override public Span setAttribute(String key, long value)    { attrs.put(key, value); return this; }
        @Override public Span setAttribute(String key, boolean value) { attrs.put(key, value); return this; }
        @Override public Span setAttribute(String key, double value) { attrs.put(key, value); return this; }

        @Override
        public Span recordException(Throwable t) {
            hasException = true;
            return this;
        }

        @Override
        public Span setStatus(SpanStatus s) {
            this.status = s;
            return this;
        }

        @Override
        public SpanScope makeCurrent() {
            return () -> { };
        }

        @Override
        public void end() {
            spans.add(new RecordedSpan(name, Map.copyOf(attrs), status, hasException));
        }
    }
}
