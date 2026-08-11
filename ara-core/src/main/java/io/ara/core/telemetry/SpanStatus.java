package io.ara.core.telemetry;

/** Status for a completed {@link Span}. */
public enum SpanStatus {
    /** The operation completed successfully. */
    OK,
    /** The operation failed; an error has been recorded on the span. */
    ERROR,
    /** No explicit status set — default for noop spans. */
    UNSET
}
