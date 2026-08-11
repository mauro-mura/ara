package io.ara.core.telemetry;

/**
 * A single unit of work tracked for distributed tracing.
 *
 * <pre>{@code
 * Span span = telemetry.spanBuilder("llm.complete")
 *     .setAttribute("llm.provider", provider)
 *     .startSpan();
 * try (var scope = span.makeCurrent()) {
 *     // ... do work ...
 *     span.setStatus(SpanStatus.OK);
 * } catch (Exception e) {
 *     span.recordException(e).setStatus(SpanStatus.ERROR);
 *     throw e;
 * } finally {
 *     span.end();
 * }
 * }</pre>
 */
public interface Span {

    Span setAttribute(String key, String value);

    Span setAttribute(String key, long value);

    Span setAttribute(String key, boolean value);

    Span setAttribute(String key, double value);

    Span recordException(Throwable t);

    Span setStatus(SpanStatus status);

    /**
     * Makes this span the current span in the active context so that child spans
     * created during the scope are automatically linked as children.
     *
     * <p>Must be used in a {@code try-with-resources} block on the same thread.
     * Closing the scope does not end the span.
     */
    SpanScope makeCurrent();

    /** Ends this span. Must be called exactly once, in a {@code finally} block. */
    void end();
}
