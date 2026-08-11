package io.ara.adapters.telemetry;

import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the parts of {@link OtelTelemetryFactory} that don't require a live
 * OTLP collector: exporter selection, and that every supported exporter/endpoint
 * shape produces a usable {@link AraTelemetry} without throwing at construction time.
 *
 * <p>Endpoint URL normalisation (the {@code /v1/traces} suffixing in the private
 * {@code buildExporter()}) is verified indirectly — the OTel exporters don't expose
 * their resolved endpoint via a public getter, so "builds without throwing, for every
 * endpoint shape the Javadoc documents as supported" is the honest level of coverage
 * without reflecting into SDK internals.
 */
class OtelTelemetryFactoryTest {

    @Test
    void exporterNone_returnsTheSharedNoopInstance() {
        AraTelemetry telemetry = OtelTelemetryFactory.builder().exporter("none").build();
        assertSame(AraTelemetry.noop(), telemetry, "exporter=none must short-circuit to AraTelemetry.noop()");
    }

    @Test
    void exporterNoneIsCaseInsensitive() {
        AraTelemetry telemetry = OtelTelemetryFactory.builder().exporter("NONE").build();
        assertSame(AraTelemetry.noop(), telemetry);
    }

    @Test
    void defaultBuilder_buildsWithoutThrowing() {
        // Defaults: serviceName="ara", exporter="otlp-http", endpoint=default localhost collector.
        // No collector is running — construction must still succeed (network I/O is deferred
        // to the async batch export thread, not performed here).
        AraTelemetry telemetry = OtelTelemetryFactory.builder().build();
        try {
            assertNotSame(AraTelemetry.noop(), telemetry);
            assertDoesNotThrow(() -> telemetry.spanBuilder("op").startSpan().end());
        } finally {
            closeIfCloseable(telemetry);
        }
    }

    @Test
    void otlpHttp_endpointWithoutV1Suffix_buildsWithoutThrowing() {
        assertBuildsAndEmits(OtelTelemetryFactory.builder()
                .exporter("otlp-http")
                .endpoint("http://localhost:4318"));
    }

    @Test
    void otlpHttp_endpointWithTrailingSlash_buildsWithoutThrowing() {
        assertBuildsAndEmits(OtelTelemetryFactory.builder()
                .exporter("otlp-http")
                .endpoint("http://localhost:4318/"));
    }

    @Test
    void otlpHttp_endpointAlreadyContainingV1_buildsWithoutThrowing() {
        assertBuildsAndEmits(OtelTelemetryFactory.builder()
                .exporter("otlp-http")
                .endpoint("http://localhost:4318/v1/traces"));
    }

    @Test
    void otlpGrpc_defaultEndpoint_buildsWithoutThrowing() {
        assertBuildsAndEmits(OtelTelemetryFactory.builder().exporter("otlp-grpc"));
    }

    @Test
    void otlpGrpc_explicitEndpoint_buildsWithoutThrowing() {
        assertBuildsAndEmits(OtelTelemetryFactory.builder()
                .exporter("otlp-grpc")
                .endpoint("http://localhost:4317"));
    }

    @Test
    void exporterSelectionIsCaseInsensitive() {
        assertBuildsAndEmits(OtelTelemetryFactory.builder().exporter("OTLP-GRPC"));
    }

    @Test
    void fromEnvironment_buildsWithoutThrowing_whenNoOtelEnvVarsSet() {
        // CI/dev environments generally don't export OTEL_* — exercising the fallback-to-
        // defaults branch, which is the one every test run outside a configured collector
        // actually takes.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("OTEL_EXPORTER_TYPE") == null,
                "skip: OTEL_EXPORTER_TYPE is set in this environment, would exercise a different branch");

        AraTelemetry telemetry = OtelTelemetryFactory.fromEnvironment();
        try {
            assertNotSame(AraTelemetry.noop(), telemetry, "default exporter is otlp-http, not none");
            assertDoesNotThrow(() -> telemetry.spanBuilder("op").startSpan().end());
        } finally {
            closeIfCloseable(telemetry);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static void assertBuildsAndEmits(OtelTelemetryFactory.Builder builder) {
        AraTelemetry telemetry = builder.build();
        try {
            Span span = assertDoesNotThrow(() -> telemetry.spanBuilder("op").startSpan());
            assertDoesNotThrow(span::end);
        } finally {
            closeIfCloseable(telemetry);
        }
    }

    private static void closeIfCloseable(AraTelemetry telemetry) {
        if (telemetry instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort cleanup of the background export thread; not under test here
            }
        }
    }
}
