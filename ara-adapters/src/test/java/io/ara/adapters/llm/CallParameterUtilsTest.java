package io.ara.adapters.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Asserts that every adapter forwards {@link LlmCallContext}'s per-call sampling parameters to
 * the provider, by capturing the JSON each one actually puts on the wire.
 *
 * <p>The capture is the point. The parameters cross two layers before leaving the process —
 * ARA's context, then langchain4j's request/model parameter merge — and the defect this
 * guards against was invisible at both: Anthropic and Ollama simply never copied the values
 * onto the request, so a {@code temperatureOverride} was dropped without any error, and the
 * call still succeeded with the wrong sampling. Asserting on adapter internals would not have
 * caught it; asserting on the request body does, and simultaneously pins the merge direction
 * (request over client-level default) that the adapters rely on langchain4j for.
 *
 * <p>A loopback {@link HttpServer} stands in for the provider: no network, no credentials, and
 * the response bodies are the minimum each provider's parser accepts.
 */
class CallParameterUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Client-level defaults, deliberately different from every per-call value asserted below. */
    private static final double CLIENT_TEMPERATURE = 0.7;
    private static final int    CLIENT_MAX_TOKENS  = 4096;

    private static final double CALL_TEMPERATURE = 0.1;
    private static final double CALL_TOP_P       = 0.9;
    private static final int    CALL_MAX_TOKENS  = 256;

    private HttpServer server;
    private BlockingQueue<String> captured;

    @BeforeEach
    void setUp() {
        captured = new ArrayBlockingQueue<>(4);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** Starts the stub provider, answering every path with {@code responseBody}. */
    private String startProvider(String responseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                captured.offer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private JsonNode requestBody() throws Exception {
        String body = captured.poll(10, TimeUnit.SECONDS);
        assertNotNull(body, "the adapter never sent a request");
        return MAPPER.readTree(body);
    }

    private static LlmCallContext contextWithOverrides() {
        return new LlmCallContext.Builder()
                .agentType("test")
                .temperature(CALL_TEMPERATURE)
                .topP(CALL_TOP_P)
                .maxOutputTokens(CALL_MAX_TOKENS)
                .build();
    }

    /** A context that overrides nothing but {@code maxOutputTokens}, which is never nullable. */
    private static LlmCallContext contextWithoutOverrides() {
        return new LlmCallContext.Builder()
                .agentType("test")
                .maxOutputTokens(CALL_MAX_TOKENS)
                .build();
    }

    private static void complete(LlmClient client, LlmCallContext context) {
        client.complete(List.of(new LlmMessage("user", "hi")), context);
    }

    // ── Stub provider responses ───────────────────────────────────────────────

    private static final String OPENAI_RESPONSE = """
            {"id":"c","object":"chat.completion","created":1,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private static final String ANTHROPIC_RESPONSE = """
            {"id":"m","type":"message","role":"assistant","model":"claude-sonnet-4-6",
             "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":1,"output_tokens":1}}""";

    private static final String OLLAMA_RESPONSE = """
            {"model":"llama3.2","created_at":"2024-01-01T00:00:00Z",
             "message":{"role":"assistant","content":"hi"},"done":true,
             "done_reason":"stop","prompt_eval_count":1,"eval_count":1}""";

    // ── OpenAI ────────────────────────────────────────────────────────────────

    @Test
    void openAiSendsThePerCallParameters() throws Exception {
        String baseUrl = startProvider(OPENAI_RESPONSE);
        LlmClient client = OpenAiLlmClient.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .modelName("gpt-4o")
                .temperature(CLIENT_TEMPERATURE)
                .maxTokens(CLIENT_MAX_TOKENS)
                .timeout(Duration.ofSeconds(10))
                .build();

        complete(client, contextWithOverrides());

        JsonNode request = requestBody();
        assertEquals(CALL_TEMPERATURE, request.get("temperature").asDouble());
        assertEquals(CALL_TOP_P,       request.get("top_p").asDouble());
        assertEquals(CALL_MAX_TOKENS,  request.get("max_tokens").asInt());
    }

    // ── Anthropic ─────────────────────────────────────────────────────────────

    @Test
    void anthropicSendsThePerCallParameters() throws Exception {
        String baseUrl = startProvider(ANTHROPIC_RESPONSE);
        LlmClient client = AnthropicLlmClient.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .modelName("claude-sonnet-4-6")
                .temperature(CLIENT_TEMPERATURE)
                .maxTokens(CLIENT_MAX_TOKENS)
                .timeout(Duration.ofSeconds(10))
                .build();

        complete(client, contextWithOverrides());

        JsonNode request = requestBody();
        assertEquals(CALL_TEMPERATURE, request.get("temperature").asDouble());
        assertEquals(CALL_TOP_P,       request.get("top_p").asDouble());
        assertEquals(CALL_MAX_TOKENS,  request.get("max_tokens").asInt());
    }

    @Test
    void anthropicKeepsTheClientDefaultWhenTheCallOverridesNothing() throws Exception {
        String baseUrl = startProvider(ANTHROPIC_RESPONSE);
        LlmClient client = AnthropicLlmClient.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .modelName("claude-sonnet-4-6")
                .temperature(CLIENT_TEMPERATURE)
                .maxTokens(CLIENT_MAX_TOKENS)
                .timeout(Duration.ofSeconds(10))
                .build();

        complete(client, contextWithoutOverrides());

        JsonNode request = requestBody();
        assertEquals(CLIENT_TEMPERATURE, request.get("temperature").asDouble());
    }

    // ── Ollama ────────────────────────────────────────────────────────────────

    @Test
    void ollamaSendsThePerCallParameters() throws Exception {
        String baseUrl = startProvider(OLLAMA_RESPONSE);
        LlmClient client = OllamaLlmClient.builder()
                .baseUrl(baseUrl)
                .modelName("llama3.2")
                .temperature(CLIENT_TEMPERATURE)
                .timeout(Duration.ofSeconds(10))
                .build();

        complete(client, contextWithOverrides());

        // Ollama nests sampling under "options", and calls the output cap "num_predict".
        JsonNode options = requestBody().get("options");
        assertEquals(CALL_TEMPERATURE, options.get("temperature").asDouble());
        assertEquals(CALL_TOP_P,       options.get("top_p").asDouble());
        assertEquals(CALL_MAX_TOKENS,  options.get("num_predict").asInt());
    }

    @Test
    void ollamaKeepsTheClientDefaultWhenTheCallOverridesNothing() throws Exception {
        String baseUrl = startProvider(OLLAMA_RESPONSE);
        LlmClient client = OllamaLlmClient.builder()
                .baseUrl(baseUrl)
                .modelName("llama3.2")
                .temperature(CLIENT_TEMPERATURE)
                .timeout(Duration.ofSeconds(10))
                .build();

        complete(client, contextWithoutOverrides());

        JsonNode options = requestBody().get("options");
        assertEquals(CLIENT_TEMPERATURE, options.get("temperature").asDouble());
    }
}
