package io.ara.adapters.llm.ollama;

import io.ara.adapters.llm.StubLlmProvider;
import io.ara.adapters.llm.StubResponses;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers how {@link OllamaLlmClient} maps an Ollama reply into an {@link LlmCompletion}.
 *
 * <p>The case that matters is a reply with no text. {@code LlmCompletion} rejects a null text
 * outright, so a mapping that only checks whether the message itself is present pushes the
 * failure into the record's constructor — an NPE from deep inside the adapter, on a reply the
 * provider is entitled to send.
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
}
