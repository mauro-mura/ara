package io.ara.adapters.embedding.openai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the HTTP/1.1 workaround on the embedding client — the counterpart of the chat client's
 * {@code forceHttp1}, added for the same failure: a vLLM behind uvicorn's {@code httptools}
 * path drops the body of any request carrying {@code Connection: Upgrade} and logs
 * {@code "Unsupported upgrade request."}, so an embedding call never reaches the model.
 *
 * <p>The assertion is that, with {@code forceHttp1(true)}, the request the server actually
 * receives is HTTP/1.1 and the call completes end-to-end. A JDK {@link HttpServer} only ever
 * speaks HTTP/1.1, so this cannot stage the HTTP/2 side of the contrast directly; what it does
 * guard is that the flag wires a working JDK client through langchain4j's
 * {@code httpClientBuilder} rather than, say, throwing or silently dropping configuration — the
 * regression that would otherwise be invisible until a real vLLM rejected the upgrade.
 */
class OpenAiEmbeddingForceHttp1Test {

    /** A 4-float embedding reply, matching dimensions(4) below — the shape LC4j's parser wants. */
    private static final String EMBEDDING_REPLY = """
            {"object":"list","model":"qwen-embedding-8b",
             "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3,0.4]}],
             "usage":{"prompt_tokens":1,"total_tokens":1}}""";

    @Test
    void force_http1_sends_an_http_1_1_request_and_completes() throws Exception {
        AtomicReference<String> protocol = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            protocol.set(exchange.getProtocol());   // e.g. "HTTP/1.1"
            exchange.getRequestBody().readAllBytes();
            byte[] out = EMBEDDING_REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            OpenAiEmbeddingClient client = OpenAiEmbeddingClient.builder()
                    .modelName("qwen-embedding-8b")
                    .dimensions(4)
                    .endpoint(baseUrl, "test-key")
                    .timeout(Duration.ofSeconds(10))
                    .forceHttp1(true)
                    .build();

            List<Float> vector = client.embed("hello");

            assertEquals(4, vector.size(), "the embedding completed end-to-end");
            assertNotNull(protocol.get(), "the server never received a request");
            assertEquals("HTTP/1.1", protocol.get(),
                    "forceHttp1(true) must put an HTTP/1.1 request on the wire");
        } finally {
            server.stop(0);
        }
    }
}
