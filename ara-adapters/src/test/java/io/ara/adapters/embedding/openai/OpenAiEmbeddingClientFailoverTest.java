package io.ara.adapters.embedding.openai;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.adapters.llm.StubLlmProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof that {@link OpenAiEmbeddingClient} actually fails over across endpoints and
 * not just that {@code EmbeddingEndpointPool}'s unit tests do (those use fake
 * {@code EmbeddingClient}s, never a real LangChain4j model or a real OpenAI-shaped response
 * body). Reuses {@link StubLlmProvider} — generic enough to serve any canned JSON body, not
 * just chat completions — to serve a genuine OpenAI embeddings response on the fallback
 * endpoint.
 */
class OpenAiEmbeddingClientFailoverTest {

    @Test
    void failsOverFromADeadPrimaryToAWorkingFallback_andParsesTheRealResponse() throws Exception {
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://127.0.0.1:" + socket.getLocalPort();
        }

        String embeddingsResponse = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3] }
                  ],
                  "model": "text-embedding-3-small",
                  "usage": { "prompt_tokens": 5, "total_tokens": 5 }
                }
                """;

        try (StubLlmProvider fallback = StubLlmProvider.answering(embeddingsResponse)) {
            OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                    .modelName("text-embedding-3-small")
                    .dimensions(3)
                    .endpoint(deadUrl, "primary-key")
                    .endpoint(fallback.baseUrl(), "fallback-key")
                    .timeout(Duration.ofSeconds(5))
                    .build();

            List<Float> vector = client.embed("hello world");

            assertEquals(List.of(0.1f, 0.2f, 0.3f), vector);

            JsonNode request = fallback.nextRequest();
            assertEquals("text-embedding-3-small", request.path("model").asText(),
                    "the fallback must have actually received a real embeddings request");
        }
    }
}
