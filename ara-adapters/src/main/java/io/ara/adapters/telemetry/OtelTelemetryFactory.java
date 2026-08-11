package io.ara.adapters.telemetry;

import io.ara.core.telemetry.AraTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Objects;

/**
 * Factory for creating {@link OtelAraTelemetry} instances.
 *
 * <p>Use the fluent {@link #builder()} to configure exporter, endpoint, and service name:
 * <pre>{@code
 * AraTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 * }</pre>
 *
 * <p>Alternatively, configure via standard OTel environment variables:
 * <pre>{@code
 * // OTEL_SERVICE_NAME=my-agent
 * // OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
 * AraTelemetry telemetry = OtelTelemetryFactory.fromEnvironment();
 * }</pre>
 *
 * <p>Supported exporters: {@code otlp-http} (default), {@code otlp-grpc}, {@code none}.
 */
public final class OtelTelemetryFactory {

    private static final String DEFAULT_ENDPOINT_HTTP = "http://localhost:4318/v1/traces";
    private static final String DEFAULT_ENDPOINT_GRPC = "http://localhost:4317";
    private static final String DEFAULT_SERVICE_NAME  = "ara";

    private OtelTelemetryFactory() {
        throw new UnsupportedOperationException("utility class");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an {@link OtelAraTelemetry} from standard OTel environment variables:
     * <ul>
     *   <li>{@code OTEL_SERVICE_NAME} — service name (default: {@code ara})</li>
     *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} — collector URL</li>
     *   <li>{@code OTEL_EXPORTER_TYPE} — {@code otlp-http} | {@code otlp-grpc} | {@code none}</li>
     * </ul>
     */
    public static AraTelemetry fromEnvironment() {
        return builder()
                .serviceName(System.getenv().getOrDefault("OTEL_SERVICE_NAME", DEFAULT_SERVICE_NAME))
                .endpoint(System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", DEFAULT_ENDPOINT_HTTP))
                .exporter(System.getenv().getOrDefault("OTEL_EXPORTER_TYPE", "otlp-http"))
                .build();
    }

    public static final class Builder {

        private String serviceName = DEFAULT_SERVICE_NAME;
        private String exporter    = "otlp-http";
        private String endpoint    = null;

        private Builder() {}

        public Builder serviceName(String v) {
            this.serviceName = Objects.requireNonNull(v, "serviceName must not be null"); return this;
        }

        /** Sets the exporter: {@code otlp-http} (default), {@code otlp-grpc}, {@code none}. */
        public Builder exporter(String v) {
            this.exporter = Objects.requireNonNull(v, "exporter must not be null"); return this;
        }

        /**
         * Sets the OTLP collector endpoint URL. For HTTP: full URL or base URL (factory
         * appends {@code /v1/traces} when absent). For gRPC: base URL only.
         */
        public Builder endpoint(String v) {
            this.endpoint = v; return this;
        }

        public AraTelemetry build() {
            if ("none".equalsIgnoreCase(exporter)) return AraTelemetry.noop();

            Resource resource = Resource.getDefault().merge(
                    Resource.create(Attributes.of(
                            AttributeKey.stringKey("service.name"), serviceName)));

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(buildExporter()).build())
                    .setResource(resource)
                    .build();

            return new OtelAraTelemetry(OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build());
        }

        private SpanExporter buildExporter() {
            return switch (exporter.toLowerCase()) {
                case "otlp-grpc" -> {
                    String ep = endpoint != null ? endpoint : DEFAULT_ENDPOINT_GRPC;
                    yield OtlpGrpcSpanExporter.builder().setEndpoint(ep).build();
                }
                default -> {
                    String ep = endpoint != null ? endpoint : DEFAULT_ENDPOINT_HTTP;
                    if (!ep.contains("/v1/")) {
                        ep = ep.replaceAll("/+$", "") + "/v1/traces";
                    }
                    yield OtlpHttpSpanExporter.builder().setEndpoint(ep).build();
                }
            };
        }
    }
}
