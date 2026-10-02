package io.ara.adapters.llm.openai;

import com.sun.net.httpserver.HttpServer;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmCompletion;
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

/**
 * Pins {@code forceHttp1} on the <em>blocking</em> chat path — the gap this test guards is that
 * {@code complete()} used to run through a default {@link dev.langchain4j.model.openai.OpenAiChatModel}
 * that ignored the flag, so a vLLM rejecting the HTTP/2 h2c upgrade ({@code "Unsupported upgrade
 * request."}) broke every non-streaming call with no way to opt out of the upgrade.
 *
 * <p>A JDK {@link HttpServer} only speaks HTTP/1.1, so this asserts the positive side: with
 * {@code forceHttp1(true)} the request {@code complete()} actually sends is HTTP/1.1 and the
 * call maps a reply end-to-end. That is enough to catch the regression — a flag wired only to
 * the streaming model would leave this request on the JDK client's default negotiation.
 */
class OpenAiForceHttp1BlockingTest {

    private static final String CHAT_REPLY = """
            {"id":"c","object":"chat.completion","created":1,"model":"gpt-oss-20b",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    @Test
    void force_http1_sends_an_http_1_1_request_on_the_blocking_path() throws Exception {
        AtomicReference<String> protocol = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            protocol.set(exchange.getProtocol());   // e.g. "HTTP/1.1"
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
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

            OpenAiLlmClient client = OpenAiLlmClient.builder()
                    .apiKey("test-key")
                    .baseUrl(baseUrl)
                    .modelName("gpt-oss-20b")
                    .timeout(Duration.ofSeconds(10))
                    .forceHttp1(true)
                    .build();

            LlmCompletion completion = client.complete(
                    List.of(LlmMessage.user("hi")), new LlmCallContext.Builder().build());

            assertEquals("hi", completion.text(), "the blocking call completed end-to-end");
            assertNotNull(protocol.get(), "the server never received a request");
            assertEquals("HTTP/1.1", protocol.get(),
                    "forceHttp1(true) must put an HTTP/1.1 request on the blocking path too");
        } finally {
            server.stop(0);
        }
    }
}
