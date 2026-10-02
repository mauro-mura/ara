package io.ara.adapters.embedding.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.adapters.llm.StubLlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the point of batching: {@code embedAll} must put exactly <em>one</em> HTTP request on
 * the wire, carrying every input in a single {@code input} array — not one request per text.
 * {@link StubLlmProvider} counts requests received and exposes the body of each, which is
 * what distinguishes this from a test against {@code EmbeddingEndpointPool}'s fakes: those
 * prove the failover policy, not that the real LangChain4j model actually batches on the wire.
 */
class OpenAiEmbeddingClientEmbedAllTest {

    @Test
    void embedAll_sends_a_single_batched_request_for_every_chunk() throws Exception {
        String embeddingsResponse = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3] },
                    { "object": "embedding", "index": 1, "embedding": [0.4, 0.5, 0.6] },
                    { "object": "embedding", "index": 2, "embedding": [0.7, 0.8, 0.9] }
                  ],
                  "model": "text-embedding-3-small",
                  "usage": { "prompt_tokens": 15, "total_tokens": 15 }
                }
                """;

        try (StubLlmProvider provider = StubLlmProvider.answering(embeddingsResponse)) {
            OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                    .modelName("text-embedding-3-small")
                    .dimensions(3)
                    .endpoint(provider.baseUrl(), "test-key")
                    .timeout(Duration.ofSeconds(5))
                    .build();

            List<String> chunks = List.of("chunk one", "chunk two", "chunk three");
            List<List<Float>> vectors = client.embedAll(chunks);

            assertEquals(3, vectors.size());
            assertEquals(List.of(0.1f, 0.2f, 0.3f), vectors.get(0));
            assertEquals(List.of(0.4f, 0.5f, 0.6f), vectors.get(1));
            assertEquals(List.of(0.7f, 0.8f, 0.9f), vectors.get(2));

            // Exactly one request: StubLlmProvider's queue would be empty on a second poll if
            // a second request had never been sent, so this also implicitly bounds the count
            // to one by only ever draining a single element.
            JsonNode request = provider.nextRequest();
            assertTrue(request.path("input").isArray(), "input must be a single batched array");
            assertEquals(3, request.path("input").size(), "all three chunks must be in one request");
            assertEquals("chunk one",   request.path("input").get(0).asText());
            assertEquals("chunk two",   request.path("input").get(1).asText());
            assertEquals("chunk three", request.path("input").get(2).asText());
        }
    }

    @Test
    void embedAll_returns_vectors_in_the_order_the_provider_lists_them() throws Exception {
        // OpenAI's actual API always lists `data` sorted by `index`, matching input order —
        // this pins what langchain4j actually does with the array: it trusts the data[] order
        // as given, it does not re-sort by the `index` field. A provider that violated its own
        // contract (unsorted data[]) would surface here as misordered vectors, same as it
        // would with any other OpenAI-compatible client.
        String embeddingsResponse = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": [1.0, 2.0] },
                    { "object": "embedding", "index": 1, "embedding": [4.0, 5.0] }
                  ],
                  "model": "text-embedding-3-small",
                  "usage": { "prompt_tokens": 2, "total_tokens": 2 }
                }
                """;

        try (StubLlmProvider provider = StubLlmProvider.answering(embeddingsResponse)) {
            OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                    .modelName("text-embedding-3-small")
                    .dimensions(2)
                    .endpoint(provider.baseUrl(), "test-key")
                    .timeout(Duration.ofSeconds(5))
                    .build();

            List<List<Float>> vectors = client.embedAll(List.of("first", "second"));

            assertEquals(List.of(1.0f, 2.0f), vectors.get(0), "first chunk's vector");
            assertEquals(List.of(4.0f, 5.0f), vectors.get(1), "second chunk's vector");
        }
    }
}
