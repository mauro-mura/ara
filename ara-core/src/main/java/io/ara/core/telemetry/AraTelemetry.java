package io.ara.core.telemetry;

/**
 * Service-provider interface for distributed tracing in ARA.
 *
 * <p>The default implementation, {@link NoopAraTelemetry}, performs no work and
 * introduces zero allocations. To activate real tracing, build an
 * {@code OtelAraTelemetry} via {@code OtelTelemetryFactory} (in {@code ara-adapters})
 * and pass it wherever telemetry is needed.
 *
 * <p>Example — programmatic wiring:
 * <pre>{@code
 * AraTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent-app")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 *
 * LlmClient instrumented = new InstrumentedLlmClient(delegate, telemetry);
 * }</pre>
 */
public interface AraTelemetry {

    /**
     * Returns a {@link SpanBuilder} for the given operation name.
     *
     * <p>The builder captures the current tracing context so spans started from it
     * are automatically children of the ambient parent span.
     *
     * @param operationName the span name (e.g. {@code "llm.complete"}); never {@code null}
     * @return a new span builder; never {@code null}
     */
    SpanBuilder spanBuilder(String operationName);

    /**
     * Captures whatever ambient tracing context is active on the calling thread and
     * returns a {@link Runnable} that re-establishes it for the duration of {@code task}.
     *
     * <p>This exists because {@code Span.makeCurrent()} does not, by itself, survive a hop
     * onto a different thread: tracing context is thread-local, so a new thread — including
     * a virtual thread spawned mid-span, e.g. by {@code ReactStrategy.dispatchParallel} for
     * concurrent tool calls, or {@code LocalMessageBus} for agent delegation — starts with
     * no ambient context. Any span started inside it would wrongly appear as a root span
     * instead of a child of the span that was current when the thread was spawned.
     *
     * <p>Callers MUST invoke this on the thread that still has the parent span current,
     * <em>before</em> spawning the new thread — capturing happens at call time, not when
     * {@code task} eventually runs:
     * <pre>{@code
     * Thread.ofVirtual().start(telemetry.propagate(() -> doWork()));
     * }</pre>
     *
     * <p>Default: returns {@code task} unchanged — {@link #noop()} has no context to
     * propagate.
     *
     * @param task the work to run, possibly on another thread
     * @return a {@link Runnable} that restores the captured context before running {@code task}
     */
    default Runnable propagate(Runnable task) {
        return task;
    }

    /** Returns the no-op singleton that produces zero-overhead spans. */
    static AraTelemetry noop() {
        return NoopAraTelemetry.INSTANCE;
    }
}
