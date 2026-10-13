package io.ara.adapters.llm;

import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.chatjimmy.ChatJimmyLlmClient;
import io.ara.adapters.llm.opencode.OpenCodeServerAdapter;
import io.ara.adapters.llm.mistral.MistralLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmClient;

import java.util.Map;

/**
 * Factory that provides builder access for all ARA-supported LLM adapters.
 *
 * <p>Each method returns the adapter's fluent {@code LlmClient.Builder}
 * so that clients can be configured and built in a single expression and wired directly into
 * {@code io.ara.runtime.AraRuntime}:
 *
 * <pre>{@code
 * LlmClient gpt4o = AraLlmClientFactory.openAi()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .model(OpenAiLlmClient.Models.GPT_4O)
 *     .build();
 *
 * LlmClient claude = AraLlmClientFactory.anthropic()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .model(AnthropicLlmClient.Models.CLAUDE_SONNET_4_6)
 *     .build();
 *
 * LlmClient llama = AraLlmClientFactory.ollama()
 *     .model(OllamaLlmClient.Models.LLAMA_3_2)
 *     .build();
 *
 * LlmClient mistral = AraLlmClientFactory.mistral()
 *     .apiKey(System.getenv("MISTRAL_API_KEY"))
 *     .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
 *     .build();
 *
 * LlmClient jimmy = AraLlmClientFactory.chatJimmy()
 *     .modelName("llama3.1-8B")
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("gpt-4o",  gpt4o)
 *     .llmClient("claude",  claude)
 *     .llmClient("local",   llama)
 *     .build();
 * }</pre>
 *
 * <p>For OpenAI-compatible endpoints (Azure OpenAI, Groq, LM Studio, Together AI, …)
 * use {@link #openAi()} and call {@code .baseUrl(String)} on the returned builder.
 *
 * <p>The same clients can be described in a configuration file instead of code — see
 * {@link #fromConfig(Map)}.
 *
 * @see OpenAiLlmClient
 * @see AnthropicLlmClient
 * @see OllamaLlmClient
 * @see MistralLlmClient
 * @see ChatJimmyLlmClient
 * @see LlmClient
 */
public final class AraLlmClientFactory {

    private AraLlmClientFactory() {}

    /**
     * Builds the named clients described by the {@code ara.llm.*} entries of a flat configuration map, so
     * that a deployment's models live in its configuration file rather than in code:
     *
     * <pre>{@code
     * ara:
     *   llm:
     *     main:                          # the name an agent refers to in llm.primary.model
     *       provider: openai
     *       model: gpt-4o
     *       apiKey: ${OPENAI_API_KEY}
     *     local:
     *       provider: ollama
     *       model: llama3.2
     * }</pre>
     *
     * <pre>{@code
     * AraRuntime.Builder runtime = AraRuntime.builder();
     * AraLlmClientFactory.fromConfig(AraYamlLoader.load()).forEach(runtime::llmClient);
     * }</pre>
     *
     * <p>The map is the one {@code io.ara.runtime.config.AraYamlLoader} returns: dotted keys, environment
     * variables already substituted ({@code ${VAR}}, {@code ${VAR:default}}). Keys other than
     * {@code ara.llm.<name>.<key>} are ignored, so the whole map can be passed. This method does not read
     * YAML itself — {@code ara-adapters} does not depend on the runtime — and a name cannot contain a dot.
     *
     * <table>
     *   <caption>Providers and their keys</caption>
     *   <tr><th>{@code provider}</th><th>keys</th></tr>
     *   <tr><td>{@code openai}</td><td>{@code model} (required), {@code apiKey} (required),
     *       {@code baseUrl}, {@code temperature}, {@code maxTokens}, {@code timeoutSeconds},
     *       {@code logRequests}, {@code logResponses}</td></tr>
     *   <tr><td>{@code anthropic}</td><td>as {@code openai}; {@code model} optional (the adapter's
     *       default applies)</td></tr>
     *   <tr><td>{@code mistral}</td><td>as {@code anthropic}</td></tr>
     *   <tr><td>{@code ollama}</td><td>{@code model}, {@code baseUrl} (both optional: a local
     *       {@code llama3.2} by default), {@code temperature}, {@code timeoutSeconds},
     *       {@code logRequests}, {@code logResponses}</td></tr>
     * </table>
     *
     * <p>An OpenAI-compatible server that needs no key still needs a value for {@code apiKey}; write it in
     * the file — {@code apiKey: ${LLM_API_KEY:not-required}} — rather than having a placeholder chosen here.
     * {@code chatJimmy} and {@code openCode} are not available from a file; build them with their builders.
     *
     * <p>The file is read strictly: an unknown key, a key of another provider, a missing required key, a
     * value that is not the number or boolean it should be — each is refused with a message that names the
     * full key. No provider is contacted: a wrong URL or key shows at the first request.
     *
     * @param config flat configuration entries, as produced by the YAML loader
     * @return the clients by name, in name order; empty if the map has no {@code ara.llm.*} entry
     * @throws IllegalArgumentException if an entry is malformed, as described above
     */
    public static Map<String, LlmClient> fromConfig(Map<String, String> config) {
        return LlmConfigReader.read(config);
    }

    /**
     * Returns a builder for an OpenAI (or OpenAI-compatible) {@link LlmClient}.
     *
     * @return {@link OpenAiLlmClient.Builder}
     */
    public static OpenAiLlmClient.Builder openAi() {
        return OpenAiLlmClient.builder();
    }

    /**
     * Returns a builder for an Anthropic Claude {@link LlmClient}.
     *
     * @return {@link AnthropicLlmClient.Builder}
     * @see AnthropicLlmClient.Models
     */
    public static AnthropicLlmClient.Builder anthropic() {
        return AnthropicLlmClient.builder();
    }

    /**
     * Returns a builder for a local Ollama {@link LlmClient}.
     *
     * <p>Defaults to {@code http://localhost:11434} — override with
     * {@link OllamaLlmClient.Builder#baseUrl(String)} for remote instances.
     *
     * @return {@link OllamaLlmClient.Builder}
     * @see OllamaLlmClient.Models
     */
    public static OllamaLlmClient.Builder ollama() {
        return OllamaLlmClient.builder();
    }

    /**
     * Returns a builder for a Mistral AI {@link LlmClient} — the adapter to reach for when a
     * task attaches a PDF that must be read as a document rather than pre-extracted to text.
     *
     * @return {@link MistralLlmClient.Builder}
     * @see MistralLlmClient.Models
     */
    public static MistralLlmClient.Builder mistral() {
        return MistralLlmClient.builder();
    }

    /**
     * Returns a builder for a <a href="https://chatjimmy.ai/">chatjimmy.ai</a>
     * {@link LlmClient}. Unlike the other adapters this one is not LangChain4j-backed — see
     * {@link ChatJimmyLlmClient} for why and for what was deliberately left out.
     *
     * @return {@link ChatJimmyLlmClient.Builder}
     */
    public static ChatJimmyLlmClient.Builder chatJimmy() {
        return ChatJimmyLlmClient.builder();
    }

    /**
     * Returns a builder for an {@link LlmClient} backed by an
     * <a href="https://opencode.ai">opencode</a> server, launched on demand on this machine or
     * reached by URL at any reachable host. Not LangChain4j-backed — see
     * {@link OpenCodeServerAdapter} for the mapping and its limits, and in particular for what
     * {@code zenFreeTierTools} grants on whichever host the server sits on.
     *
     * @return {@link OpenCodeServerAdapter.Builder}
     */
    public static OpenCodeServerAdapter.Builder openCode() {
        return OpenCodeServerAdapter.builder();
    }
}
