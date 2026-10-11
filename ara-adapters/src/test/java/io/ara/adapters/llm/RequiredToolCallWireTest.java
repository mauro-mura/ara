package io.ara.adapters.llm;

import com.sun.net.httpserver.HttpServer;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmMessage;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each adapter puts on the wire when a call requires a tool call
 * ({@link LlmCallContext#requireToolCall()}): {@code tool_choice} where the provider takes it, and
 * nothing, rather than a failed call, where it does not.
 */
class RequiredToolCallWireTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Serves {@code reply} to any request and remembers the last request's body. */
    private String serve(String reply) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String wire() {
        return body.get().replaceAll("\\s+", "");
    }

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echoes"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success("echo", "ok"); }
    };

    private static LlmCallContext.Builder withEcho() {
        return new LlmCallContext.Builder().resolvedTools(List.of(ECHO));
    }

    private static final List<LlmMessage> HI = List.of(LlmMessage.user("hi"));

    // ── OpenAI ─────────────────────────────────────────────────────────────────────────

    private static final String OPENAI_REPLY = """
            {"id":"c","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"the answer"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private OpenAiLlmClient openAi() throws Exception {
        return OpenAiLlmClient.builder().apiKey("k").baseUrl(serve(OPENAI_REPLY) + "/v1").modelName("m")
                .timeout(Duration.ofSeconds(10)).forceHttp1(true).build();
    }

    @Test
    void openAi_aRequiredToolCall_isSentAsToolChoiceRequired() throws Exception {
        openAi().complete(HI, withEcho().requireToolCall(true).build());

        assertTrue(wire().contains("\"tool_choice\":\"required\""), body.get());
    }

    @Test
    void openAi_withoutTheRequirement_sendsNoToolChoice() throws Exception {
        openAi().complete(HI, withEcho().build());

        assertFalse(body.get().contains("tool_choice"), "the request is what it always was: " + body.get());
    }

    @Test
    void openAi_aRequirementWithNoToolToCall_isNotSent() throws Exception {
        // tool_choice "required" with no tools is an error on OpenAI: there is nothing to require.
        openAi().complete(HI, new LlmCallContext.Builder().resolvedTools(List.of()).requireToolCall(true).build());

        assertFalse(body.get().contains("tool_choice"), body.get());
    }

    // ── Anthropic ──────────────────────────────────────────────────────────────────────

    private static final String ANTHROPIC_REPLY = """
            {"id":"m","type":"message","role":"assistant","model":"claude",
             "content":[{"type":"thinking","thinking":"Thinking.","signature":"sig"},
                        {"type":"text","text":"the answer"}],
             "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""";

    private AnthropicLlmClient anthropic() throws Exception {
        return AnthropicLlmClient.builder().apiKey("k").baseUrl(serve(ANTHROPIC_REPLY) + "/v1/")
                .modelName("claude").timeout(Duration.ofSeconds(10)).withoutTemperature().build();
    }

    @Test
    void anthropic_withoutThinking_requiresAnyTool() throws Exception {
        anthropic().complete(HI, withEcho().requireToolCall(true).build());

        assertTrue(wire().contains("\"tool_choice\":{\"type\":\"any\""), body.get());
    }

    @Test
    void anthropic_whileThinking_leavesTheChoiceToTheModel() throws Exception {
        // Anthropic refuses tool_choice "any" with extended thinking on: the call would fail.
        anthropic().complete(HI, withEcho().requireToolCall(true).thinkingBudgetTokens(1024).build());

        assertTrue(wire().contains("\"thinking\""), body.get());
        assertFalse(wire().contains("\"type\":\"any\""), body.get());
    }

    // ── Ollama ─────────────────────────────────────────────────────────────────────────

    private static final String OLLAMA_REPLY = """
            {"model":"m","created_at":"2026-01-01T00:00:00Z",
             "message":{"role":"assistant","content":"the answer"},
             "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}""";

    @Test
    void ollama_cannotRequireATool_soTheCallGoesOutWithoutIt() throws Exception {
        OllamaLlmClient client = OllamaLlmClient.builder().baseUrl(serve(OLLAMA_REPLY)).modelName("m")
                .nativeTools(true).timeout(Duration.ofSeconds(10)).build();

        // langchain4j's Ollama model throws on any tool choice but AUTO; the call must still be made.
        client.complete(HI, withEcho().requireToolCall(true).build());

        assertTrue(wire().contains("\"tools\""), "the tools still go: " + body.get());
        assertFalse(body.get().contains("tool_choice"), body.get());
    }
}
