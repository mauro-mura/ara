package io.ara.adapters.llm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.adapters.llm.StubLlmProvider;
import io.ara.adapters.llm.StubResponses;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what {@link OllamaLlmClient} sends and what it makes of the reply.
 *
 * <p>Two things are worth pinning here. A reply with no text: {@code LlmCompletion} rejects a
 * null text outright, so a mapping that only checks whether the message itself is present
 * pushes the failure into the record's constructor — an NPE from deep inside the adapter, on a
 * reply the provider is entitled to send. And the native-tools switch: it has to stay off
 * unless asked for, because {@code ReactStrategy} attaches resolved tools to the context for
 * every client regardless of whether it can use them.
 */
class OllamaLlmClientTest {

    private static LlmClient clientPointedAt(StubLlmProvider provider) {
        return OllamaLlmClient.builder()
                .baseUrl(provider.baseUrl())
                .modelName("llama3.2")
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    private static LlmCompletion complete(LlmClient client) {
        return client.complete(
                List.of(new LlmMessage("user", "hi")),
                new LlmCallContext.Builder().agentType("test").build());
    }

    @Test
    void mapsATextlessReplyToAnEmptyCompletionInsteadOfFailing() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA_TOOL_CALL_ONLY)) {
            LlmCompletion completion = complete(clientPointedAt(provider));

            assertNotNull(completion);
            assertEquals("", completion.text());
        }
    }

    @Test
    void mapsAnOrdinaryReply() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA)) {
            LlmCompletion completion = complete(clientPointedAt(provider));

            assertEquals("hi", completion.text());
            assertEquals(1, completion.promptTokens());
            assertEquals(1, completion.outputTokens());
        }
    }

    // ── Native tools (opt-in) ─────────────────────────────────────────────────

    /** Minimal tool; only its schema and id reach the provider. */
    private record ProbeTool(String toolId, String description, String argumentSchema) implements AraTool {
        @Override public ToolResult execute(String argumentJson) {
            throw new UnsupportedOperationException("not part of what these tests exercise");
        }
    }

    private static LlmCallContext contextWithTools() {
        return new LlmCallContext.Builder()
                .agentType("test")
                .resolvedTools(List.<AraTool>of(new ProbeTool("get_weather", "looks up weather",
                        """
                        {"type":"object","properties":{"city":{"type":"string"}},
                         "required":["city"]}""")))
                .build();
    }

    private static LlmClient clientPointedAt(StubLlmProvider provider, boolean nativeTools) {
        return OllamaLlmClient.builder()
                .baseUrl(provider.baseUrl())
                .modelName("llama3.1")
                .nativeTools(nativeTools)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void reportsNativeToolSupportOnlyWhenEnabled() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA)) {
            assertFalse(clientPointedAt(provider).supportsNativeTools());
            assertFalse(clientPointedAt(provider, false).supportsNativeTools());
            assertTrue(clientPointedAt(provider, true).supportsNativeTools());
        }
    }

    @Test
    void sendsToolsWhenNativeToolsIsEnabled() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA)) {
            clientPointedAt(provider, true)
                    .complete(List.of(new LlmMessage("user", "hi")), contextWithTools());

            JsonNode tools = provider.nextRequest().get("tools");
            assertEquals(1, tools.size());
            assertEquals("get_weather", tools.get(0).get("function").get("name").asText());
        }
    }

    @Test
    void withholdsToolsByDefaultEvenWhenTheContextCarriesThem() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA)) {
            // ReactStrategy attaches resolved tools to the context for every client, native or
            // not, so this is the ordinary case for a default-built Ollama client — not an
            // artificial one.
            clientPointedAt(provider).complete(List.of(new LlmMessage("user", "hi")), contextWithTools());

            JsonNode tools = provider.nextRequest().get("tools");
            assertTrue(tools == null || tools.isEmpty(), "tools must not be sent unless enabled");
        }
    }

    @Test
    void mapsAToolCallReply() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(StubResponses.OLLAMA_TOOL_CALL_ONLY)) {
            LlmCompletion completion = clientPointedAt(provider, true)
                    .complete(List.of(new LlmMessage("user", "hi")), contextWithTools());

            assertTrue(completion.hasToolCall());
            assertEquals("tool_calls", completion.finishReason());
            assertEquals(1, completion.toolCalls().size());
            assertEquals("get_weather", completion.toolCalls().get(0).toolId());
            assertTrue(completion.toolCalls().get(0).argumentJson().contains("Rome"));
        }
    }
}
