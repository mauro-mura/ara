package io.ara.core.telemetry;

/**
 * No-op implementation of {@link AraTelemetry} with zero overhead.
 *
 * <p>All methods return shared singleton objects that perform no work.
 * This is the default used by all ARA components when no telemetry is configured.
 */
public final class NoopAraTelemetry implements AraTelemetry {

    static final NoopAraTelemetry INSTANCE = new NoopAraTelemetry();

    static final Span NOOP_SPAN = new NoopSpan();
    static final SpanScope NOOP_SCOPE = () -> {};
    static final SpanBuilder NOOP_BUILDER = new NoopSpanBuilder();

    private NoopAraTelemetry() {}

    @Override
    public SpanBuilder spanBuilder(String operationName) {
        return NOOP_BUILDER;
    }

    static final class NoopSpanBuilder implements SpanBuilder {
        @Override public SpanBuilder setAttribute(String key, String value)  { return this; }
        @Override public SpanBuilder setAttribute(String key, long value)    { return this; }
        @Override public SpanBuilder setAttribute(String key, boolean value) { return this; }
        @Override public Span startSpan() { return NOOP_SPAN; }
    }

    static final class NoopSpan implements Span {
        @Override public Span setAttribute(String key, String value)   { return this; }
        @Override public Span setAttribute(String key, long value)     { return this; }
        @Override public Span setAttribute(String key, boolean value)  { return this; }
        @Override public Span setAttribute(String key, double value)   { return this; }
        @Override public Span recordException(Throwable t)             { return this; }
        @Override public Span setStatus(SpanStatus status)             { return this; }
        @Override public SpanScope makeCurrent()                       { return NOOP_SCOPE; }
        @Override public void end()                                    {}
    }
}
