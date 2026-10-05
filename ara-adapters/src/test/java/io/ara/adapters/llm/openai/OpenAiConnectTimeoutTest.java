package io.ara.adapters.llm.openai;

import com.sun.net.httpserver.HttpServer;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code connectTimeout}, which bounds the TCP/TLS handshake independently of the
 * request {@code timeout} langchain4j applies to the whole call.
 *
 * <p>The gap it closes: a candidate in a {@code FAILOVER} pool pointing at an unreachable
 * endpoint used to cost the <em>full</em> request timeout on every single call before the pool
 * could advance — 30s of dead wait per request for a host that will never answer, paid again on
 * each request until the circuit breaker had seen enough failures to open.
 *
 * <p>The elapsed-time assertion is deliberately one-sided (well under the request timeout rather
 * than close to the connect timeout): an environment that rejects the route immediately returns
 * even faster, which is still correct, while a build that dropped the knob would wait the full
 * request budget and fail.
 */
class OpenAiConnectTimeoutTest {

    private static final String CHAT_REPLY = """
            {"id":"c","object":"chat.completion","created":1,"model":"gpt-oss-20b",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    /**
     * TEST-NET-2 (RFC 5737), reserved for documentation and not routable — a connection attempt
     * either hangs until the connect timeout or is refused by the local stack. Both outcomes
     * satisfy the assertion below; only ignoring the connect timeout does not.
     */
    private static final String UNROUTABLE_BASE_URL = "http://198.51.100.1:9/v1";

    @Test
    void connectTimeout_boundsTheHandshakeWellBeforeTheRequestTimeout() {
        OpenAiLlmClient client = OpenAiLlmClient.builder()
                .apiKey("test-key")
                .baseUrl(UNROUTABLE_BASE_URL)
                .modelName("gpt-oss-20b")
                // The separation under test: a long request budget, a short handshake budget.
                .timeout(Duration.ofSeconds(30))
                .connectTimeout(Duration.ofSeconds(1))
                .build();

        long startedAt = System.nanoTime();
        LlmException failure = assertThrows(LlmException.class, () -> client.complete(
                List.of(LlmMessage.user("hi")), new LlmCallContext.Builder().build()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(15)) < 0,
                () -> "connectTimeout was ignored — the call waited " + elapsed
                        + ", i.e. the request timeout rather than the connect timeout");
        assertTrue(failure.shouldFailover(),
                () -> "an unreachable endpoint must let a failover pool advance, got "
                        + failure.errorType());
    }

    @Test
    void connectTimeout_composesWithForceHttp1OnASingleHttpClient() throws Exception {
        AtomicReference<String> protocol = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            protocol.set(exchange.getProtocol());
            exchange.getRequestBody().readAllBytes();
            byte[] out = CHAT_REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        server.start();
        try {
            OpenAiLlmClient client = OpenAiLlmClient.builder()
                    .apiKey("test-key")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .modelName("gpt-oss-20b")
                    .timeout(Duration.ofSeconds(10))
                    // Both overrides must travel on the same JDK client: langchain4j takes a
                    // single httpClientBuilder, so one would otherwise replace the other.
                    .forceHttp1(true)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            LlmCompletion completion = client.complete(
                    List.of(LlmMessage.user("hi")), new LlmCallContext.Builder().build());

            assertEquals("hi", completion.text(), "the call completed end-to-end");
            assertNotNull(protocol.get(), "the server never received a request");
            assertEquals("HTTP/1.1", protocol.get(),
                    "setting connectTimeout must not displace forceHttp1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectTimeout_rejectsNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () ->
                OpenAiLlmClient.builder().connectTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                OpenAiLlmClient.builder().connectTimeout(Duration.ofSeconds(-1)));
    }
}
