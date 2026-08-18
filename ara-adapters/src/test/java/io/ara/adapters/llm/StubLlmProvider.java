package io.ara.adapters.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A provider API standing in for OpenAI/Anthropic/Ollama on loopback: answers every path with
 * one canned body and keeps the request bodies it received.
 *
 * <p>Adapter tests need this rather than a mocked chat model because the behaviour worth
 * pinning down lives past the adapter — in what langchain4j finally serialises. Asserting on
 * the request body is the only way to catch a value the adapter forgot to forward, or a merge
 * that resolved the wrong way, and it costs no network and no credentials.
 */
public final class StubLlmProvider implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final BlockingQueue<String> received = new ArrayBlockingQueue<>(8);

    private StubLlmProvider(String responseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                received.offer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    /** Starts a stub answering every request with {@code responseBody}. */
    public static StubLlmProvider answering(String responseBody) throws Exception {
        return new StubLlmProvider(responseBody);
    }

    /** Base URL to point an adapter at. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The next request body received, parsed. Fails the test if none arrives. */
    public JsonNode nextRequest() throws Exception {
        String body = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(body, "the adapter never sent a request");
        return MAPPER.readTree(body);
    }

    @Override public void close() {
        server.stop(0);
    }
}
