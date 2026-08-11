package io.ara.core.telemetry;

/**
 * A handle to the active span context set by {@link Span#makeCurrent()}.
 *
 * <p>Must be closed in a {@code try-with-resources} block on the same thread
 * that called {@code makeCurrent()}. Closing does not end the span.
 */
public interface SpanScope extends AutoCloseable {

    /** Closes this scope and restores the previous context. Never throws. */
    @Override
    void close();
}
