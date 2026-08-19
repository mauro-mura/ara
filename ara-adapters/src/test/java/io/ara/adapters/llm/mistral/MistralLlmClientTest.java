package io.ara.adapters.llm.mistral;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.adapters.llm.StubLlmProvider;
import io.ara.adapters.llm.StubResponses;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
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
 * What the Mistral adapter actually puts on the wire.
 *
 * <p>The document assertion is the reason this adapter exists: it has to leave as a Mistral
 * {@code document_url} part, not as text and not as an image. That is only observable in the
 * serialised request body, so the test reads it from a stub provider on loopback rather than
 * mocking the chat model — a mock would happily confirm a conversion langchain4j never
 * performs.
 */
class MistralLlmClientTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 contract".getBytes(StandardCharsets.UTF_8);

    private static MistralLlmClient clientPointedAt(StubLlmProvider provider) {
        return MistralLlmClient.builder()
                .apiKey("test-key")
                .baseUrl(provider.baseUrl())
                .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    private static LlmCallContext contextWith(MediaStore store) {
        return new LlmCallContext.Builder()
                .agentType("test")
                .mediaResolver(MediaResolver.backedBy(store))
                .build();
    }

    @Test
    void a_pdf_leaves_as_a_native_mistral_document_part() throws Exception {
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);

        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.MISTRAL)) {
            clientPointedAt(provider).complete(
                    List.of(LlmMessage.user("check the recess clause", List.of(pdf))),
                    contextWith(store));

            JsonNode content = provider.nextRequest().get("messages").get(0).get("content");
            assertTrue(content.isArray(), "a message with media must serialise as content parts");

            boolean hasDocument = false;
            for (JsonNode part : content) {
                if ("document_url".equals(part.path("type").asText())) {
                    hasDocument = true;
                    assertTrue(part.path("document_url").asText().contains("base64,"),
                            "the stored bytes must travel inline as base64");
                }
            }
            assertTrue(hasDocument, "no document part in " + content);
        }
    }

    @Test
    void the_text_precedes_the_document_in_the_serialised_parts() throws Exception {
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);

        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.MISTRAL)) {
            clientPointedAt(provider).complete(
                    List.of(LlmMessage.user("check the recess clause", List.of(pdf))),
                    contextWith(store));

            JsonNode content = provider.nextRequest().get("messages").get(0).get("content");
            assertEquals("text", content.get(0).path("type").asText());
            assertEquals("check the recess clause", content.get(0).path("text").asText());
            assertEquals("document_url", content.get(content.size() - 1).path("type").asText());
        }
    }

    @Test
    void a_text_only_message_carries_one_text_part_and_no_framing() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.MISTRAL)) {
            clientPointedAt(provider).complete(
                    List.of(LlmMessage.user("just words")), contextWith(MediaStore.inMemory()));

            // langchain4j's Mistral mapper always serialises user content as a parts array,
            // media or not — so the invariant worth pinning is the parts themselves: exactly
            // the user's words, and none of the framing that only belongs with attachments.
            JsonNode content = provider.nextRequest().get("messages").get(0).get("content");
            assertEquals(1, content.size(), "one part, since there is nothing but text: " + content);
            assertEquals("text", content.get(0).path("type").asText());
            assertEquals("just words", content.get(0).path("text").asText());
        }
    }

    @Test
    void maps_an_ordinary_reply() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.MISTRAL)) {
            LlmCompletion completion = clientPointedAt(provider).complete(
                    List.of(LlmMessage.user("hi")), contextWith(MediaStore.noop()));

            assertEquals("hi", completion.text());
            assertEquals("stop", completion.finishReason());
            assertEquals(1, completion.promptTokens());
            assertEquals(1, completion.outputTokens());
        }
    }

    @Test
    void declares_documents_native_tools_and_a_provider_id() {
        MistralLlmClient client = MistralLlmClient.builder()
                .apiKey("test-key")
                .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
                .build();

        assertTrue(client.supportsNativeTools());
        assertTrue(client.supportedMediaTypes().contains("application/pdf"),
                "the native document path is why this adapter was added");
        assertTrue(client.supportedMediaTypes().contains("image/png"));
        assertEquals("mistral-mistral-medium-latest", client.providerId());
    }

    @Test
    void an_api_key_is_required() {
        assertThrows(IllegalStateException.class, () -> MistralLlmClient.builder().build());
    }

    @Test
    void an_http_failure_becomes_an_LlmException_naming_the_provider() throws Exception {
        // Points at a closed port: nothing is listening, so the call cannot succeed.
        StubLlmProvider provider = StubLlmProvider.answering(StubResponses.MISTRAL);
        String deadUrl = provider.baseUrl();
        provider.close();

        MistralLlmClient client = MistralLlmClient.builder()
                .apiKey("test-key")
                .baseUrl(deadUrl)
                .timeout(Duration.ofSeconds(2))
                .build();

        LlmException ex = assertThrows(LlmException.class, () -> client.complete(
                List.of(LlmMessage.user("hi")), contextWith(MediaStore.noop())));
        assertEquals("mistral", ex.provider());
    }
}
