package io.ara.adapters.telemetry;

import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanScope;
import io.ara.core.telemetry.SpanStatus;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link OtelAraTelemetry} against a real OpenTelemetry SDK backed by
 * {@link InMemorySpanExporter} — no fake, no network. {@link SimpleSpanProcessor}
 * exports synchronously on {@code span.end()}, so exported spans are visible to
 * assertions immediately without polling or sleeps.
 *
 * <p>The scenario this exists to protect is documented on {@link OtelAraTelemetry}
 * itself: parent-child span linkage must survive a hop onto a virtual thread, since
 * {@code ReactStrategy.dispatchParallel} and {@code LocalMessageBus} both start
 * virtual threads mid-span. That link relies entirely on {@code Context.current()}
 * being captured at {@code spanBuilder(...)} time and re-established via
 * {@code Span.makeCurrent()} on the child thread — nothing else wires it up.
 */
class OtelAraTelemetryTest {

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk      sdk;
    private OtelAraTelemetry      telemetry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        telemetry = new OtelAraTelemetry(sdk);
    }

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    private SpanData only(String name) {
        List<SpanData> matches = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .toList();
        assertEquals(1, matches.size(), "expected exactly one span named " + name);
        return matches.get(0);
    }

    // ── Attributes / status / exceptions ────────────────────────────────────────

    @Test
    void span_exportsAllAttributeTypes() {
        telemetry.spanBuilder("op")
                .setAttribute("str", "value")
                .setAttribute("num", 42L)
                .setAttribute("flag", true)
                .startSpan()
                .setAttribute("dbl", 3.14)
                .end();

        SpanData span = only("op");
        var attrs = span.getAttributes();
        assertEquals("value", attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("str")));
        assertEquals(42L,     attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("num")));
        assertEquals(true,    attrs.get(io.opentelemetry.api.common.AttributeKey.booleanKey("flag")));
        assertEquals(3.14,    attrs.get(io.opentelemetry.api.common.AttributeKey.doubleKey("dbl")));
    }

    @Test
    void span_nullStringAttribute_isExportedAsEmptyString() {
        // OtelSpanBuilder/OtelSpan both guard `value != null ? value : ""` — verify the
        // guard actually reaches the exported span rather than throwing or dropping the key.
        telemetry.spanBuilder("op").setAttribute("str", (String) null).startSpan().end();

        SpanData span = only("op");
        assertEquals("", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("str")));
    }

    @Test
    void span_statusOk_mapsToOtelOk() {
        telemetry.spanBuilder("op").startSpan().setStatus(SpanStatus.OK).end();
        assertEquals(StatusCode.OK, only("op").getStatus().getStatusCode());
    }

    @Test
    void span_statusError_mapsToOtelError() {
        telemetry.spanBuilder("op").startSpan().setStatus(SpanStatus.ERROR).end();
        assertEquals(StatusCode.ERROR, only("op").getStatus().getStatusCode());
    }

    @Test
    void span_statusUnset_mapsToOtelUnset() {
        telemetry.spanBuilder("op").startSpan().end();
        assertEquals(StatusCode.UNSET, only("op").getStatus().getStatusCode());
    }

    @Test
    void span_recordException_attachesExceptionEvent() {
        telemetry.spanBuilder("op").startSpan()
                .recordException(new IllegalStateException("boom"))
                .end();

        SpanData span = only("op");
        assertEquals(1, span.getEvents().size());
        assertEquals("exception", span.getEvents().get(0).getName());
    }

    @Test
    void span_recordExceptionWithNull_doesNotThrowAndAddsNoEvent() {
        telemetry.spanBuilder("op").startSpan().recordException(null).end();
        assertTrue(only("op").getEvents().isEmpty());
    }

    // ── Parent/child linkage ─────────────────────────────────────────────────────

    @Test
    void span_withoutAmbientContext_isARootSpan() {
        telemetry.spanBuilder("root").startSpan().end();
        assertFalse(only("root").getParentSpanContext().isValid());
    }

    @Test
    void span_startedWhileParentIsCurrent_becomesItsChild_sameThread() {
        Span parent = telemetry.spanBuilder("parent").startSpan();
        try (SpanScope scope = parent.makeCurrent()) {
            telemetry.spanBuilder("child").startSpan().end();
        } finally {
            parent.end();
        }

        SpanData parentData = only("parent");
        SpanData childData  = only("child");
        assertEquals(parentData.getSpanId(), childData.getParentSpanId());
        assertEquals(parentData.getTraceId(), childData.getTraceId());
    }

    /**
     * Documents the pitfall {@link AraTelemetry#propagate} exists to fix: OpenTelemetry's
     * {@code Context} is thread-local, so {@code makeCurrent()} on the calling thread is
     * NOT visible to a {@code spanBuilder(...)} call made from a virtual thread spawned
     * while it was current — even though the parent span is still open. Without an
     * explicit propagation step, a naively-spawned virtual thread always produces a root
     * span, never a child. (Verified independently against the raw OTel API, outside this
     * codebase, before wiring the fix — this is standard OTel behaviour, not an ARA bug.)
     */
    @Test
    void span_withoutPropagate_virtualThreadHop_breaksLinkage() throws InterruptedException {
        AtomicReference<SpanData> childRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Span parent = telemetry.spanBuilder("agent.execute").startSpan();
        try (SpanScope scope = parent.makeCurrent()) {
            Thread.ofVirtual().start(() -> {
                telemetry.spanBuilder("tool.execute").startSpan().end();
                childRef.set(only("tool.execute"));
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS), "virtual thread did not complete in time");
        } finally {
            parent.end();
        }

        assertFalse(childRef.get().getParentSpanContext().isValid(),
                "without propagate(), a span started on a freshly-spawned virtual thread "
                + "has no ambient parent — this is the bug propagate() fixes");
    }

    /**
     * The actual regression case: wrapping the spawned {@link Runnable} with
     * {@link AraTelemetry#propagate} — exactly as {@code ReactStrategy.dispatchParallel}
     * and {@code LocalMessageBus} now do before {@code Thread.ofVirtual().start(...)} —
     * must make the parent span current again inside the virtual thread, so the child
     * span it starts is correctly linked.
     */
    @Test
    void span_withPropagate_virtualThreadHop_preservesLinkage() throws InterruptedException {
        AtomicReference<SpanData> childRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Span parent = telemetry.spanBuilder("agent.execute").startSpan();
        try (SpanScope scope = parent.makeCurrent()) {
            Runnable work = () -> {
                telemetry.spanBuilder("tool.execute").startSpan().end();
                childRef.set(only("tool.execute"));
                done.countDown();
            };
            Thread.ofVirtual().start(telemetry.propagate(work));
            assertTrue(done.await(5, TimeUnit.SECONDS), "virtual thread did not complete in time");
        } finally {
            parent.end();
        }

        SpanData parentData = only("agent.execute");
        SpanData childData  = childRef.get();
        assertNotNull(childData, "child span was never exported");
        assertEquals(parentData.getSpanId(), childData.getParentSpanId(),
                "child span started on a propagate()-wrapped virtual thread must be linked "
                + "to the parent that was current when the thread was spawned");
        assertEquals(parentData.getTraceId(), childData.getTraceId());
    }

    @Test
    void span_onVirtualThread_withoutAmbientContext_isStillARootSpan() throws InterruptedException {
        // Sanity check for the previous test: a virtual thread that never observes an
        // ambient span must NOT spuriously pick up an unrelated parent.
        AtomicReference<SpanData> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            telemetry.spanBuilder("orphan").startSpan().end();
            result.set(only("orphan"));
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(result.get().getParentSpanContext().isValid());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    @Test
    void close_shutsDownUnderlyingSdk_withoutThrowing() {
        assertDoesNotThrow(() -> telemetry.close());
        // idempotent — AraAgent/AgentFactory shutdown paths may call close() more than once
        assertDoesNotThrow(() -> telemetry.close());
    }

    @Test
    void close_onNonSdkOpenTelemetry_isANoOp() {
        OtelAraTelemetry nonSdk = new OtelAraTelemetry(io.opentelemetry.api.OpenTelemetry.noop());
        assertDoesNotThrow(nonSdk::close);
    }
}
