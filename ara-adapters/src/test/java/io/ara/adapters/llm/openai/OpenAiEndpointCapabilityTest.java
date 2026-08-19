package io.ara.adapters.llm.openai;

import io.ara.adapters.llm.StubLlmProvider;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmException.ErrorType;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaResolver;
import io.ara.core.media.MediaStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Media support belongs to the <em>endpoint</em>, not to "OpenAI": this adapter is meant to be
 * pointed at any OpenAI-compatible API, and while they all take {@code image_url} parts, many
 * reject the {@code file} part a PDF becomes.
 *
 * <p>The failure this pins down was observed against a real corporate gateway: a PDF produced
 * {@code {"detail":"{'message': 'Unknown part type: file None', 'type': 'BadRequestError',
 * 'code': 400}"}} — a provider error the caller has to reverse-engineer, instead of ARA naming
 * the unsupported type before the request left. And because the adapter's catch-all reported
 * that 400 as a network error, it was retryable: the strategy retried it and a failover pool
 * would have tried it against every fallback in turn.
 */
class OpenAiEndpointCapabilityTest {

    private static final byte[] PDF = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);

    // ── Which types each endpoint shape claims ────────────────────────────────

    @Test
    void hosted_openai_claims_documents() {
        var client = OpenAiLlmClient.builder().apiKey("k").modelName("gpt-4o").build();
        assertTrue(client.supportedMediaTypes().contains("application/pdf"));
        assertTrue(client.supportedMediaTypes().contains("image/png"));
    }

    @Test
    void a_custom_base_url_does_not_claim_documents_but_still_claims_images() {
        var client = OpenAiLlmClient.builder()
                .apiKey("k").baseUrl("https://gateway.internal/v1").modelName("m").build();

        assertFalse(client.supportedMediaTypes().contains("application/pdf"),
                "an endpoint whose document support is unknown must be treated as unsupported");
        assertTrue(client.supportedMediaTypes().contains("image/png"),
                "images are the part every OpenAI-compatible endpoint accepts");
        assertTrue(client.supportedMediaTypes().contains("text/plain"));
    }

    @Test
    void documentSupport_opts_a_compatible_endpoint_back_in() {
        var client = OpenAiLlmClient.builder()
                .apiKey("k").baseUrl("https://azure.example/v1").modelName("m")
                .documentSupport(true).build();

        assertTrue(client.supportedMediaTypes().contains("application/pdf"));
    }

    @Test
    void documentSupport_can_refuse_documents_even_on_hosted_openai() {
        var client = OpenAiLlmClient.builder()
                .apiKey("k").modelName("gpt-4o").documentSupport(false).build();

        assertFalse(client.supportedMediaTypes().contains("application/pdf"));
    }

    // ── What that changes at the call ─────────────────────────────────────────

    @Test
    void a_pdf_sent_to_a_proxied_endpoint_fails_before_the_request_leaves() throws Exception {
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF);

        // The stub fails the test if reached: the point is that nothing goes out.
        try (StubLlmProvider provider = StubLlmProvider.failingWith(500, "{\"error\":\"must not be called\"}")) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .timeout(Duration.ofSeconds(5)).build();

            LlmException ex = assertThrows(LlmException.class, () -> client.complete(
                    List.of(LlmMessage.user("read it", List.of(pdf))),
                    new LlmCallContext.Builder().mediaResolver(MediaResolver.backedBy(store)).build()));

            assertFalse(ex.isRetryable(), "no fallback can accept a type this endpoint refuses");
            assertEquals(ErrorType.UNSUPPORTED_OPERATION, ex.errorType());
            assertTrue(ex.getMessage().contains("application/pdf"), ex.getMessage());
            assertTrue(ex.getMessage().contains("openai-m"), ex.getMessage());
        }
    }

    @Test
    void a_provider_400_is_non_retryable_instead_of_looking_like_a_network_error() throws Exception {
        String body = "{\"detail\":\"{'message': 'Unknown part type: file None', "
                + "'type': 'BadRequestError', 'code': 400}\"}";

        try (StubLlmProvider provider = StubLlmProvider.failingWith(400, body)) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .timeout(Duration.ofSeconds(5)).build();

            LlmException ex = assertThrows(LlmException.class, () -> client.complete(
                    List.of(LlmMessage.user("hi")), new LlmCallContext.Builder().build()));

            assertFalse(ex.isRetryable(),
                    "retrying a malformed request burns a strategy iteration and every fallback");
            assertEquals(ErrorType.INVALID_REQUEST, ex.errorType());
            assertEquals("openai", ex.provider());
        }
    }

    @Test
    void a_provider_500_stays_retryable() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.failingWith(500, "{\"error\":\"boom\"}")) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .timeout(Duration.ofSeconds(5)).build();

            LlmException ex = assertThrows(LlmException.class, () -> client.complete(
                    List.of(LlmMessage.user("hi")), new LlmCallContext.Builder().build()));

            assertTrue(ex.isRetryable(), "a server error is exactly the case worth retrying");
        }
    }
}
