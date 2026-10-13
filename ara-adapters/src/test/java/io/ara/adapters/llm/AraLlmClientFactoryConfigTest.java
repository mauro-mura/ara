package io.ara.adapters.llm;

import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.mistral.MistralLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AraLlmClientFactory#fromConfig(Map)}: named clients from {@code ara.llm.*} entries.
 *
 * <p>A client's {@code providerId()} is {@code <provider>-<model>}, which is the only thing observable
 * without a network, so the model is what these tests use to show that a value in the map reached the
 * builder. Nothing here contacts a provider: a builder records settings.
 *
 * <p>Most of the class pins the refusals. Each message must name the full key, because the person reading
 * it has a configuration file open rather than a stack trace.
 */
class AraLlmClientFactoryConfigTest {

    private static Map<String, String> config(String... keysAndValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static String refusal(Map<String, String> config) {
        return assertThrows(IllegalArgumentException.class, () -> AraLlmClientFactory.fromConfig(config))
                .getMessage();
    }

    // ── what is built ───────────────────────────────────────────────────────

    @Test
    void everyProvider_isBuilt_withItsModel() {
        Map<String, LlmClient> clients = AraLlmClientFactory.fromConfig(config(
                "ara.llm.gpt.provider", "openai", "ara.llm.gpt.model", "gpt-4o", "ara.llm.gpt.apiKey", "k",
                "ara.llm.claude.provider", "anthropic", "ara.llm.claude.model", "claude-x", "ara.llm.claude.apiKey", "k",
                "ara.llm.mistral.provider", "mistral", "ara.llm.mistral.model", "mistral-x", "ara.llm.mistral.apiKey", "k",
                "ara.llm.local.provider", "ollama", "ara.llm.local.model", "qwen3"));

        assertInstanceOf(OpenAiLlmClient.class, clients.get("gpt"));
        assertInstanceOf(AnthropicLlmClient.class, clients.get("claude"));
        assertInstanceOf(MistralLlmClient.class, clients.get("mistral"));
        assertInstanceOf(OllamaLlmClient.class, clients.get("local"));
        assertTrue(clients.get("gpt").providerId().endsWith("-gpt-4o"), clients.get("gpt").providerId());
        assertTrue(clients.get("claude").providerId().endsWith("-claude-x"), clients.get("claude").providerId());
        assertTrue(clients.get("mistral").providerId().endsWith("-mistral-x"), clients.get("mistral").providerId());
        assertTrue(clients.get("local").providerId().endsWith("-qwen3"), clients.get("local").providerId());
    }

    @Test
    void everyKeyOfAProvider_isAccepted() {
        Map<String, LlmClient> clients = AraLlmClientFactory.fromConfig(config(
                "ara.llm.a.provider", "openai", "ara.llm.a.model", "m", "ara.llm.a.apiKey", "k",
                "ara.llm.a.baseUrl", "http://127.0.0.1:1/v1", "ara.llm.a.temperature", "0.2",
                "ara.llm.a.maxTokens", "512", "ara.llm.a.timeoutSeconds", "30",
                "ara.llm.a.logRequests", "true", "ara.llm.a.logResponses", "FALSE",
                "ara.llm.b.provider", "ollama", "ara.llm.b.model", "m", "ara.llm.b.baseUrl", "http://127.0.0.1:1",
                "ara.llm.b.temperature", "0", "ara.llm.b.timeoutSeconds", "5",
                "ara.llm.b.logRequests", "false", "ara.llm.b.logResponses", "true"));

        assertEquals(2, clients.size());
    }

    @Test
    void aModelLeftOut_takesTheAdaptersDefault_whereItHasOne() {
        Map<String, LlmClient> clients = AraLlmClientFactory.fromConfig(config(
                "ara.llm.claude.provider", "anthropic", "ara.llm.claude.apiKey", "k",
                "ara.llm.mistral.provider", "mistral", "ara.llm.mistral.apiKey", "k",
                "ara.llm.local.provider", "ollama"));

        assertEquals(AnthropicLlmClient.builder().apiKey("k").build().providerId(), clients.get("claude").providerId());
        assertEquals(MistralLlmClient.builder().apiKey("k").build().providerId(), clients.get("mistral").providerId());
        assertEquals(OllamaLlmClient.builder().build().providerId(), clients.get("local").providerId());
    }

    @Test
    void providerNames_areCaseInsensitive() {
        Map<String, LlmClient> clients = AraLlmClientFactory.fromConfig(config(
                "ara.llm.a.provider", "OpenAI", "ara.llm.a.model", "m", "ara.llm.a.apiKey", "k"));

        assertInstanceOf(OpenAiLlmClient.class, clients.get("a"));
    }

    @Test
    void theResult_isInNameOrder_andNotModifiable() {
        Map<String, LlmClient> clients = AraLlmClientFactory.fromConfig(config(
                "ara.llm.zeta.provider", "ollama", "ara.llm.alpha.provider", "ollama", "ara.llm.mid.provider", "ollama"));

        assertEquals(List.of("alpha", "mid", "zeta"), List.copyOf(clients.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> clients.put("x", clients.get("mid")));
    }

    @Test
    void keysOutsideAraLlm_areIgnored_soTheWholeMapCanBePassed() {
        Map<String, LlmClient> clients = AraLlmClientFactory.fromConfig(config(
                "ara.gateway.port", "9210", "ara.runtime.name", "x", "unrelated", "y",
                "ara.llm.local.provider", "ollama"));

        assertEquals(1, clients.size());
    }

    @Test
    void noLlmEntries_isNoClients() {
        assertTrue(AraLlmClientFactory.fromConfig(Map.of()).isEmpty());
        assertTrue(AraLlmClientFactory.fromConfig(Map.of("ara.gateway.port", "9210")).isEmpty());
    }

    @Test
    void aNullMap_isRefused() {
        assertThrows(NullPointerException.class, () -> AraLlmClientFactory.fromConfig(null));
    }

    // ── what is refused, and how it says so ─────────────────────────────────

    @Test
    void aMissingProvider_isRefused() {
        assertTrue(refusal(config("ara.llm.main.model", "gpt-4o")).contains("ara.llm.main.provider is required"));
    }

    @Test
    void aBlankProvider_isAMissingProvider() {
        assertTrue(refusal(config("ara.llm.main.provider", "  ")).contains("ara.llm.main.provider is required"));
    }

    @Test
    void anUnknownProvider_isRefused_listingTheKnownOnes() {
        String message = refusal(config("ara.llm.main.provider", "gemini"));

        assertTrue(message.contains("ara.llm.main.provider: unknown 'gemini'"), message);
        assertTrue(message.contains("openai") && message.contains("anthropic")
                && message.contains("mistral") && message.contains("ollama"), message);
    }

    @Test
    void theProvidersThatNeedCodeNotAFile_areNotAvailable() {
        assertTrue(refusal(config("ara.llm.x.provider", "openCode")).contains("unknown 'openCode'"));
        assertTrue(refusal(config("ara.llm.x.provider", "chatJimmy")).contains("unknown 'chatJimmy'"));
    }

    @Test
    void aTypoInAKey_isRefused_notSilentlyIgnored() {
        String message = refusal(config("ara.llm.main.provider", "openai", "ara.llm.main.modle", "gpt-4o",
                "ara.llm.main.apiKey", "k"));

        assertTrue(message.contains("ara.llm.main.modle: unknown key for provider 'openai'"), message);
    }

    @Test
    void aKeyOfAnotherProvider_isRefused() {
        // Ollama takes no API key; accepting one would make it silently do nothing.
        String message = refusal(config("ara.llm.local.provider", "ollama", "ara.llm.local.apiKey", "k"));

        assertTrue(message.contains("ara.llm.local.apiKey: unknown key for provider 'ollama'"), message);
    }

    @Test
    void openAiWithoutAModel_isRefused_becauseItsBuilderHasNoDefault() {
        assertTrue(refusal(config("ara.llm.main.provider", "openai", "ara.llm.main.apiKey", "k"))
                .contains("ara.llm.main.model is required"));
    }

    @Test
    void aMissingApiKey_isRefused_withAHintAboutTheEnvironmentVariable() {
        String message = refusal(config("ara.llm.main.provider", "anthropic"));

        assertTrue(message.contains("ara.llm.main.apiKey is required for provider 'anthropic'"), message);
        assertTrue(message.contains("environment variable"), message);
    }

    @Test
    void anApiKeyThatIsAnUnsetVariable_isAMissingKey() {
        // What AraYamlLoader makes of ${OPENAI_API_KEY} when the variable is not set: an empty string.
        assertTrue(refusal(config("ara.llm.main.provider", "openai", "ara.llm.main.model", "m",
                "ara.llm.main.apiKey", "")).contains("ara.llm.main.apiKey is required"));
    }

    @Test
    void numbersAndBooleans_areStrict_andTheMessageQuotesTheValue() {
        Map<String, String> base = config("ara.llm.main.provider", "openai", "ara.llm.main.model", "m",
                "ara.llm.main.apiKey", "k");

        for (String[] bad : new String[][] {
                {"temperature", "warm", "must be a number"},
                {"maxTokens", "1.5", "must be a whole number"},
                {"maxTokens", "0", "must be greater than zero"},
                {"timeoutSeconds", "-3", "must be greater than zero"},
                {"logRequests", "yes", "must be true or false"}}) {
            Map<String, String> config = new HashMap<>(base);
            config.put("ara.llm.main." + bad[0], bad[1]);

            String message = refusal(config);

            assertTrue(message.contains("ara.llm.main." + bad[0] + " " + bad[2] + ", got '" + bad[1] + "'"), message);
        }
    }

    @Test
    void aKeyThatIsNotNameDotKey_isRefused() {
        assertTrue(refusal(config("ara.llm.main", "openai")).contains("ara.llm.<name>.<key>"));
        assertTrue(refusal(config("ara.llm..model", "m")).contains("ara.llm.<name>.<key>"));
        assertTrue(refusal(config("ara.llm.main.", "m")).contains("ara.llm.<name>.<key>"));
    }

    @Test
    void oneBadEntry_failsTheWholeLoad_soNoHalfConfiguredRuntimeStarts() {
        String message = refusal(config(
                "ara.llm.good.provider", "ollama",
                "ara.llm.bad.provider", "openai"));

        assertTrue(message.contains("ara.llm.bad"), message);
    }
}
