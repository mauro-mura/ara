package io.ara.adapters.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 */
class CallParameterUtilsTest {

    /** Client-level defaults, deliberately different from every per-call value asserted below. */
    private static final double CLIENT_TEMPERATURE = 0.7;
    private static final int    CLIENT_MAX_TOKENS  = 4096;

    private static final double CALL_TEMPERATURE = 0.1;
    private static final double CALL_TOP_P       = 0.9;
    private static final int    CALL_MAX_TOKENS  = 256;

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

    // ── OpenAI ────────────────────────────────────────────────────────────────

    @Test
    void openAiSendsThePerCallParameters() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OPENAI)) {
            LlmClient client = OpenAiLlmClient.builder()
                    .apiKey("test-key")
                    .baseUrl(provider.baseUrl())
                    .modelName("gpt-4o")
                    .temperature(CLIENT_TEMPERATURE)
                    .maxTokens(CLIENT_MAX_TOKENS)
                    .timeout(Duration.ofSeconds(10))
                    .build();

            complete(client, contextWithOverrides());

            JsonNode request = provider.nextRequest();
            assertEquals(CALL_TEMPERATURE, request.get("temperature").asDouble());
            assertEquals(CALL_TOP_P,       request.get("top_p").asDouble());
            assertEquals(CALL_MAX_TOKENS,  request.get("max_tokens").asInt());
        }
    }

    // ── Anthropic ─────────────────────────────────────────────────────────────

    @Test
    void anthropicSendsThePerCallParameters() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.ANTHROPIC)) {
            LlmClient client = anthropicPointedAt(provider);

            complete(client, contextWithOverrides());

            JsonNode request = provider.nextRequest();
            assertEquals(CALL_TEMPERATURE, request.get("temperature").asDouble());
            assertEquals(CALL_TOP_P,       request.get("top_p").asDouble());
            assertEquals(CALL_MAX_TOKENS,  request.get("max_tokens").asInt());
        }
    }

    @Test
    void anthropicKeepsTheClientDefaultWhenTheCallOverridesNothing() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.ANTHROPIC)) {
            LlmClient client = anthropicPointedAt(provider);

            complete(client, contextWithoutOverrides());

            assertEquals(CLIENT_TEMPERATURE, provider.nextRequest().get("temperature").asDouble());
        }
    }

    private static LlmClient anthropicPointedAt(StubLlmProvider provider) {
        return AnthropicLlmClient.builder()
                .apiKey("test-key")
                .baseUrl(provider.baseUrl())
                .modelName("claude-sonnet-4-6")
                .temperature(CLIENT_TEMPERATURE)
                .maxTokens(CLIENT_MAX_TOKENS)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Ollama ────────────────────────────────────────────────────────────────

    @Test
    void ollamaSendsThePerCallParameters() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA)) {
            LlmClient client = ollamaPointedAt(provider);

            complete(client, contextWithOverrides());

            // Ollama nests sampling under "options", and calls the output cap "num_predict".
            JsonNode options = provider.nextRequest().get("options");
            assertEquals(CALL_TEMPERATURE, options.get("temperature").asDouble());
            assertEquals(CALL_TOP_P,       options.get("top_p").asDouble());
            assertEquals(CALL_MAX_TOKENS,  options.get("num_predict").asInt());
        }
    }

    @Test
    void ollamaKeepsTheClientDefaultWhenTheCallOverridesNothing() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA)) {
            LlmClient client = ollamaPointedAt(provider);

            complete(client, contextWithoutOverrides());

            JsonNode options = provider.nextRequest().get("options");
            assertEquals(CLIENT_TEMPERATURE, options.get("temperature").asDouble());
        }
    }

    private static LlmClient ollamaPointedAt(StubLlmProvider provider) {
        return OllamaLlmClient.builder()
                .baseUrl(provider.baseUrl())
                .modelName("llama3.2")
                .temperature(CLIENT_TEMPERATURE)
                .timeout(Duration.ofSeconds(10))
                .build();
    }
}
