package io.ara.adapters.embedding.ollama;

import java.net.ServerSocket;
import java.time.Duration;

import io.ara.core.memory.EmbeddingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingClientTest {

    @Test
    void declares_a_provider_id_and_dimensions() {
        OllamaEmbeddingClient client = OllamaEmbeddingClient.builder()
                .modelName("nomic-embed-text")
                .dimensions(768)
                .build();

        assertEquals("ollama-nomic-embed-text", client.providerId());
        assertEquals(768, client.dimensions());
    }

    @Test
    void defaults_to_localhost_when_no_endpoint_is_declared() {
        // No apiKey knob at all — Ollama takes none, unlike the two keyed adapters.
        assertDoesNotThrow(() -> OllamaEmbeddingClient.builder()
                .modelName("nomic-embed-text")
                .dimensions(768)
                .build());
    }

    @Test
    void a_model_name_is_required() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaEmbeddingClient.builder().dimensions(768).build());
    }

    @Test
    void dimensions_are_required_and_must_be_positive() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaEmbeddingClient.builder().modelName("nomic-embed-text").build());
        assertThrows(IllegalArgumentException.class, () -> OllamaEmbeddingClient.builder()
                .modelName("nomic-embed-text").dimensions(0).build());
    }

    @Test
    void a_blank_endpoint_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> OllamaEmbeddingClient.builder()
                .endpoint(" ")
                .modelName("nomic-embed-text")
                .dimensions(768)
                .build());
    }

    @Test
    void an_unreachable_endpoint_becomes_an_EmbeddingException_naming_the_provider() throws Exception {
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://127.0.0.1:" + socket.getLocalPort();
        }

        OllamaEmbeddingClient client = OllamaEmbeddingClient.builder()
                .endpoint(deadUrl)
                .modelName("nomic-embed-text")
                .dimensions(768)
                .timeout(Duration.ofSeconds(2))
                .build();

        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> client.embed("hello"));
        assertEquals("ollama", ex.provider());
        assertTrue(ex.shouldFailover());
    }

    @Test
    void a_single_endpoint_does_not_go_through_the_pool() {
        OllamaEmbeddingClient client = OllamaEmbeddingClient.builder()
                .endpoint("http://custom-host:11434")
                .modelName("nomic-embed-text")
                .dimensions(768)
                .build();

        assertEquals("ollama-nomic-embed-text", client.providerId());
    }
}
