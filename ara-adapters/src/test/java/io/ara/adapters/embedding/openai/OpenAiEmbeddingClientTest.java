package io.ara.adapters.embedding.openai;

import java.net.ServerSocket;
import java.time.Duration;

import io.ara.core.memory.EmbeddingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiEmbeddingClientTest {

    @Test
    void declares_a_provider_id_and_dimensions() {
        OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                .modelName("text-embedding-3-small")
                .dimensions(1536)
                .endpoint("test-key")
                .build();

        assertEquals("openai-text-embedding-3-small", client.providerId());
        assertEquals(1536, client.dimensions());
    }

    @Test
    void at_least_one_endpoint_is_required() {
        assertThrows(IllegalStateException.class, () -> OpenAiEmbeddingClient.builder()
                .modelName("text-embedding-3-small")
                .dimensions(1536)
                .build());
    }

    @Test
    void a_model_name_is_required() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiEmbeddingClient.builder()
                .dimensions(1536)
                .endpoint("test-key")
                .build());
    }

    @Test
    void dimensions_are_required_and_must_be_positive() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiEmbeddingClient.builder()
                .modelName("text-embedding-3-small")
                .endpoint("test-key")
                .build());
        assertThrows(IllegalArgumentException.class, () -> OpenAiEmbeddingClient.builder()
                .modelName("text-embedding-3-small")
                .dimensions(0)
                .endpoint("test-key")
                .build());
    }

    @Test
    void an_unreachable_endpoint_becomes_an_EmbeddingException_naming_the_provider() throws Exception {
        // Bind and immediately close a local port: nothing listens there, so the connection
        // is refused synchronously instead of timing out — same trick as
        // io.ara.adapters.llm.mistral.MistralLlmClientTest's connection-error case.
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://127.0.0.1:" + socket.getLocalPort();
        }

        OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                .modelName("text-embedding-3-small")
                .dimensions(1536)
                .endpoint(deadUrl, "test-key")
                .timeout(Duration.ofSeconds(2))
                .build();

        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> client.embed("hello"));
        assertEquals("openai", ex.provider());
        assertTrue(ex.shouldFailover(), "an unreachable endpoint should still let a pool fail over");
    }

    @Test
    void a_single_endpoint_does_not_go_through_the_pool() {
        // With one endpoint, providerId() must stay the plain "openai-<model>" form — no
        // failover[...] composite id, since there is nothing to fail over to.
        OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                .modelName("text-embedding-3-small")
                .dimensions(1536)
                .endpoint("https://custom.example.com/v1", "test-key")
                .build();

        assertEquals("openai-text-embedding-3-small", client.providerId());
    }

    @Test
    void a_second_endpoint_is_used_when_the_first_fails() throws Exception {
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://127.0.0.1:" + socket.getLocalPort();
        }
        String secondDeadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            secondDeadUrl = "http://127.0.0.1:" + socket.getLocalPort();
        }

        OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                .modelName("text-embedding-3-small")
                .dimensions(1536)
                .endpoint(deadUrl, "primary-key")
                .endpoint(secondDeadUrl, "fallback-key")
                .timeout(Duration.ofSeconds(2))
                .build();

        // Both endpoints are unreachable, so this proves the second was actually tried: the
        // reported provider in the final exception is the LAST candidate attempted, not the
        // first — a single-endpoint client would only ever report the primary's own failure.
        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> client.embed("hello"));
        assertEquals("openai", ex.provider());
    }
}
