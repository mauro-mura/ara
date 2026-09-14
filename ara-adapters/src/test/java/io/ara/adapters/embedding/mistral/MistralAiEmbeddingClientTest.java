package io.ara.adapters.embedding.mistral;

import java.net.ServerSocket;
import java.time.Duration;

import io.ara.core.memory.EmbeddingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MistralAiEmbeddingClientTest {

    @Test
    void defaults_the_model_to_mistral_embed() {
        MistralAiEmbeddingClient client = MistralAiEmbeddingClient.builder()
                .dimensions(1024)
                .endpoint("test-key")
                .build();

        assertEquals("mistral-mistral-embed", client.providerId());
        assertEquals(1024, client.dimensions());
    }

    @Test
    void at_least_one_endpoint_is_required() {
        assertThrows(IllegalStateException.class,
                () -> MistralAiEmbeddingClient.builder().dimensions(1024).build());
    }

    @Test
    void dimensions_are_required_and_must_be_positive() {
        assertThrows(IllegalArgumentException.class,
                () -> MistralAiEmbeddingClient.builder().endpoint("test-key").build());
        assertThrows(IllegalArgumentException.class,
                () -> MistralAiEmbeddingClient.builder().endpoint("test-key").dimensions(-1).build());
    }

    @Test
    void an_unreachable_endpoint_becomes_an_EmbeddingException_naming_the_provider() throws Exception {
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://127.0.0.1:" + socket.getLocalPort();
        }

        MistralAiEmbeddingClient client = MistralAiEmbeddingClient.builder()
                .dimensions(1024)
                .endpoint(deadUrl, "test-key")
                .timeout(Duration.ofSeconds(2))
                .build();

        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> client.embed("hello"));
        assertEquals("mistral", ex.provider());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void a_single_endpoint_does_not_go_through_the_pool() {
        MistralAiEmbeddingClient client = MistralAiEmbeddingClient.builder()
                .dimensions(1024)
                .endpoint("https://custom.example.com", "test-key")
                .build();

        assertEquals("mistral-mistral-embed", client.providerId());
    }
}
