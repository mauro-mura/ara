package io.ara.runtime.llm;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaRef;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.factory.FailoverLlmClient;
import io.ara.runtime.stubs.RoutingLlmClient;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a composite reports the <em>intersection</em> of its delegates' media types,
 * and that the resulting mismatch is a hard stop rather than a fallback.
 *
 * <p>The union would be the tempting answer, and it is the dangerous one: a pool holding one
 * PDF-capable client and one text-only client would then accept a PDF and either succeed or
 * silently answer from nothing, depending on which delegate the pool happened to pick.
 */
class SupportedMediaTypesCompositionTest {

    private record Capable(String providerId, Set<String> supportedMediaTypes) implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }
    }

    /** Refuses anything with media, exactly as an adapter does for a type it cannot send. */
    private record TextOnly(String providerId) implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            for (LlmMessage m : messages) {
                for (MediaRef ref : m.media()) {
                    throw LlmException.unsupportedMediaType(
                            providerId, ref.mimeType(), ref.name(), Set.of());
                }
            }
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }
    }

    private static final MediaRef PDF =
            new MediaRef("digest", "application/pdf", "contract.pdf", 10, null);

    @Test
    void failover_reports_only_what_every_candidate_supports() {
        LlmClient pool = new FailoverLlmClient(List.of(
                new Capable("a", Set.of("image/png", "application/pdf")),
                new Capable("b", Set.of("image/png"))));

        assertEquals(Set.of("image/png"), pool.supportedMediaTypes());
    }

    @Test
    void failover_reports_nothing_when_one_candidate_is_text_only() {
        LlmClient pool = new FailoverLlmClient(List.of(
                new Capable("a", Set.of("image/png", "application/pdf")),
                new TextOnly("b")));

        assertEquals(Set.of(), pool.supportedMediaTypes());
    }

    /** Fails the test if the failover ever reaches it. */
    private record NeverReached(String providerId, Set<String> supportedMediaTypes) implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            throw new AssertionError("the non-retryable failure must abort the failover");
        }
    }

    @Test
    void failover_does_not_try_a_fallback_when_media_is_unsupported() {
        LlmClient pool = new FailoverLlmClient(List.of(
                new TextOnly("primary"), new NeverReached("fallback", Set.of("application/pdf"))));

        LlmException ex = assertThrows(LlmException.class, () -> pool.complete(
                List.of(LlmMessage.user("read it", List.of(PDF))), (LlmCallContext) null));
        assertFalse(ex.isRetryable());
        assertEquals("primary", ex.provider());
    }

    @Test
    void routing_reports_the_intersection_of_every_route_and_the_default() {
        LlmClient routing = RoutingLlmClient.builder()
                .route("writer", new Capable("w", Set.of("image/png", "application/pdf")))
                .route("reader", new Capable("r", Set.of("application/pdf")))
                .defaultClient(new Capable("d", Set.of("image/png", "application/pdf")))
                .build();

        assertEquals(Set.of("application/pdf"), routing.supportedMediaTypes());
    }

    @Test
    void leaf_stubs_that_wrap_nothing_report_text_only() {
        assertEquals(Set.of(), ScriptedLlmClient.script().thenFinalAnswer("x").build().supportedMediaTypes());
    }

    @Test
    void a_single_wrap_decorator_reports_the_delegate_set() {
        LlmClient capable = new Capable("inner", Set.of("application/pdf"));
        assertEquals(Set.of("application/pdf"),
                new InstrumentedLlmClient(capable, AraTelemetry.noop()).supportedMediaTypes());
        assertEquals(Set.of("application/pdf"),
                new LoggingLlmClient(capable, 100).supportedMediaTypes());
    }
}
