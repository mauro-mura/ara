package io.ara.core.telemetry;

/**
 * Fluent builder for creating a {@link Span}.
 *
 * <pre>{@code
 * Span span = telemetry.spanBuilder("llm.complete")
 *     .setAttribute("llm.provider", providerId)
 *     .setAttribute("llm.model", model)
 *     .startSpan();
 * }</pre>
 */
public interface SpanBuilder {

    SpanBuilder setAttribute(String key, String value);

    SpanBuilder setAttribute(String key, long value);

    SpanBuilder setAttribute(String key, boolean value);

    /**
     * Starts and returns the span. The caller must call {@link Span#end()} in a
     * {@code finally} block.
     */
    Span startSpan();
}
