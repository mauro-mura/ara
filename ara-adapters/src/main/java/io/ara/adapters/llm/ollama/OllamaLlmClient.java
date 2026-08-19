package io.ara.adapters.llm.ollama;

import io.ara.adapters.llm.CallParameterUtils;
import io.ara.adapters.llm.TokenStreamPublisher;
import io.ara.adapters.llm.ToolConversionUtils;
import io.ara.core.llm.*;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/**
 * {@link LlmClient} adapter for <a href="https://ollama.com/">Ollama</a>,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Ollama runs open-weight models locally (or on a self-hosted server).
 * No API key is needed — only a running Ollama instance and the desired model pulled via
 * {@code ollama pull <model>}.
 *
 * <pre>{@code
 * LlmClient llama = OllamaLlmClient.builder()
 *     .baseUrl("http://localhost:11434")
 *     .model(OllamaLlmClient.Models.LLAMA_3_2)
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("llama3", llama)
 *     .build();
 * }</pre>
 *
 * <h2>Versioned model tags</h2>
 * <p>Use {@link Builder#modelName(String)} for versioned tags not in the {@link Models} enum:
 * <pre>{@code
 * .modelName("llama3.2:70b")
 * .modelName("qwen2.5-coder:32b")
 * }</pre>
 *
 * <h2>Function calling</h2>
 * <p>Off by default: tools in {@link LlmCallContext} are ignored and the strategy's
 * prompt-based routing applies. Whether a model can use tools natively depends on the model
 * rather than on Ollama — {@code llama3.1} and later, {@code qwen2.5} and {@code mistral-nemo}
 * can, {@code llama3}, {@code gemma} and {@code phi} cannot — and this client has no reliable
 * way to tell which one it is talking to, so enabling it is left to the caller:
 * <pre>{@code
 * LlmClient llama = OllamaLlmClient.builder()
 *     .model(OllamaLlmClient.Models.LLAMA_3_1)
 *     .nativeTools(true)
 *     .build();
 * }</pre>
 * See {@link Builder#nativeTools(boolean)} for what goes wrong if it is enabled for a model
 * that does not support tools.
 *
 * @see LlmClient
 * @see OllamaLlmClient.Models
 */
public class OllamaLlmClient implements LlmClient {

    private static final String PROVIDER = "Ollama";

    private final OllamaChatModel          chatModel;
    private final OllamaStreamingChatModel streamingModel;
    private final String                   modelName;
    private final boolean                  nativeTools;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Base model families available via Ollama.
     *
     * <p>These are the canonical base names used with {@code ollama pull}. For specific
     * size variants (e.g. {@code llama3.2:70b}) use {@link Builder#modelName(String)}.
     * Context windows are sourced from the
     * <a href="https://ollama.com/library">Ollama library</a> (last verified: 2026-04).
     */
    public enum Models {
        // Llama 3.x (Meta)
        LLAMA_3_2          ("llama3.2",      128_000),
        LLAMA_3_1          ("llama3.1",      128_000),
        LLAMA_3            ("llama3",          8_192),
        // Mistral / Mixtral
        MISTRAL            ("mistral",        32_768),
        MIXTRAL            ("mixtral",        32_768),
        MISTRAL_NEMO       ("mistral-nemo",  128_000),
        // Qwen 2.5 (Alibaba)
        QWEN_2_5           ("qwen2.5",       128_000),
        QWEN_2_5_CODER     ("qwen2.5-coder", 128_000),
        // Gemma (Google)
        GEMMA_3            ("gemma3",          8_192),
        GEMMA_2            ("gemma2",          8_192),
        // Phi (Microsoft)
        PHI_4              ("phi4",           16_384),
        PHI_3_5            ("phi3.5",          4_096),
        // DeepSeek
        DEEPSEEK_R1        ("deepseek-r1",   128_000),
        DEEPSEEK_CODER_V2  ("deepseek-coder-v2", 128_000),
        // Code-focused
        CODELLAMA          ("codellama",      16_384);

        /** Ollama model identifier (base name, without size tag). */
        public final String id;
        /** Maximum context window in tokens. */
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id            = id;
            this.contextWindow = contextWindow;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private OllamaLlmClient(Builder builder) {
        this.modelName     = builder.modelName;
        this.nativeTools   = builder.nativeTools;
        this.chatModel     = OllamaChatModel.builder()
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();
        this.streamingModel = OllamaStreamingChatModel.builder()
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .timeout(builder.timeout)
                .build();
    }

    // ── LlmClient ─────────────────────────────────────────────────────────────

    @Override
    public String providerId() {
        return "ollama-" + modelName;
    }

    /** Reflects {@link Builder#nativeTools(boolean)} — see its javadoc for why this is opt-in. */
    @Override
    public boolean supportsNativeTools() {
        return nativeTools;
    }

    /**
     * Images and text files, but <strong>not</strong> PDFs: Ollama's chat API has no document
     * part, so there is nothing for a PDF to become. It is declared unsupported rather than
     * silently converted or dropped, which makes a PDF sent here a non-retryable failure
     * naming the provider — the alternative would be an answer about a document the model
     * never received.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.TEXT);
    }

    /**
     * Sends {@code messages} to the local Ollama instance and blocks until completion.
     *
     * <p>Tools from {@link LlmCallContext} are forwarded only when the client was built with
     * {@link Builder#nativeTools(boolean)}; otherwise they are ignored and the strategy's
     * prompt-based tool routing applies.
     *
     * @param messages the conversation history (system → user → assistant turns)
     * @param context  per-call parameters (temperature, max tokens)
     * @return the model's completion
     * @throws LlmException on network errors or unexpected Ollama server failures
     */
    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        try {
            ChatRequest.Builder reqBuilder = ChatRequest.builder()
                    .messages(toLC4jMessages(messages, context));
            CallParameterUtils.applyTo(reqBuilder, context);
            applyToolsIfEnabled(reqBuilder, context);

            ChatResponse response = chatModel.chat(reqBuilder.build());
            return toLlmCompletion(response);

        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw mapException(ex);
        }
    }

    /**
     * Streams tokens from the Ollama streaming endpoint.
     *
     * <p>Each token is emitted individually via {@link Flow.Publisher}. The stream completes
     * when Ollama sends a {@code done} signal, or exceptionally on connection errors.
     *
     * @param messages the conversation history
     * @param context  per-call parameters
     * @return a {@link Flow.Publisher} of token strings
     */
    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return TokenStreamPublisher.of(
                handler -> {
                    ChatRequest.Builder reqBuilder = ChatRequest.builder()
                            .messages(toLC4jMessages(messages, context));
                    CallParameterUtils.applyTo(reqBuilder, context);
                    applyToolsIfEnabled(reqBuilder, context);

                    streamingModel.chat(reqBuilder.build(), handler);
                },
                this::mapException);
    }

    /**
     * Forwards the call's tools, but only when this client was built for a tool-capable model.
     *
     * <p>The {@code nativeTools} guard is not redundant with {@code hasResolvedTools()}:
     * {@code ReactStrategy} attaches resolved tools to the context unconditionally, for every
     * client, so keying off the context alone would send tool specifications to a model that
     * cannot use them the moment any agent has tools registered.
     */
    private void applyToolsIfEnabled(ChatRequest.Builder reqBuilder, LlmCallContext context) {
        if (nativeTools && context != null && context.hasResolvedTools()) {
            reqBuilder.toolSpecifications(ToolConversionUtils.toolSpecificationsFor(context));
        }
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private List<ChatMessage> toLC4jMessages(List<LlmMessage> messages, LlmCallContext context) {
        // Native tool-call and tool-result turns reach this client from two directions: its
        // own, once nativeTools is on, and another provider's — a session using
        // ROUND_ROBIN/FAILOVER can hand it a history whose earlier turns were answered by
        // OpenAI or Anthropic. Delegating here keeps both intact instead of degrading them to
        // a confusing generic user turn, and applies the shared media check and flattening —
        // see ToolConversionUtils.toNativeAwareChatMessages.
        return ToolConversionUtils.toNativeAwareChatMessages(messages, context, this);
    }

    private LlmCompletion toLlmCompletion(ChatResponse response) {
        // Both halves matter: AiMessage.text() is null on a response that carries no text
        // (a tool-call-only turn), and LlmCompletion rejects a null text outright — so
        // guarding only the message, as this did, moved the failure to the record's
        // constructor instead of preventing it. Same shape as the other two adapters.
        var ai = response.aiMessage();
        String text = (ai != null && ai.text() != null) ? ai.text() : "";
        String finishReason = null;
        int inputTokens  = 0;
        int outputTokens = 0;

        if (response.metadata() != null) {
            if (response.metadata().finishReason() != null) {
                finishReason = response.metadata().finishReason().toString().toLowerCase();
            }
            if (response.metadata().tokenUsage() != null) {
                inputTokens  = response.metadata().tokenUsage().inputTokenCount();
                outputTokens = response.metadata().tokenUsage().outputTokenCount();
            }
        }

        if (finishReason == null) finishReason = "stop";

        String toolCallJson = null;
        String toolCallId   = null;
        List<ToolCallEntry> toolCalls = List.of();
        if (ai != null && ai.hasToolExecutionRequests()) {
            // Mapped whether or not nativeTools is on: a model only emits these when tools
            // were sent, so there is nothing to gate, and dropping them if one ever arrived
            // would lose the call silently. Ollama identifies tool calls by name and sends no
            // call id, so toolCallId stays null here — ToolCallEntry documents it as nullable
            // and the runtime checks before using it.
            var requests = ai.toolExecutionRequests();
            toolCalls    = ToolConversionUtils.toToolCallEntries(requests);
            toolCallJson = ToolConversionUtils.toLegacyToolCallJson(requests.get(0));
            toolCallId   = requests.get(0).id();
            finishReason = "tool_calls";
        }

        return new LlmCompletion(text, inputTokens, outputTokens, finishReason,
                toolCallJson, toolCallId, toolCalls);
    }

    private LlmException mapException(Throwable ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        if (msg.contains("Connection refused") || msg.contains("connect")) {
            return LlmException.networkError(PROVIDER,
                    "Cannot reach Ollama at the configured base URL. Is Ollama running?", ex);
        }
        if (msg.contains("404") || msg.contains("model not found") || msg.contains("pull model")) {
            return LlmException.modelNotFound(PROVIDER, modelName);
        }
        return LlmException.networkError(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link OllamaLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link OllamaLlmClient}.
     *
     * <p>Defaults: base URL {@code http://localhost:11434}, model {@link Models#LLAMA_3_2},
     * timeout 5 minutes (Ollama can be slow on first run).
     */
    public static final class Builder {
        private String   baseUrl      = "http://localhost:11434";
        private String   modelName    = Models.LLAMA_3_2.id;
        private Double   temperature;
        private Duration timeout      = Duration.ofMinutes(5);
        private boolean  logRequests  = false;
        private boolean  logResponses = false;
        private boolean  nativeTools  = false;

        /** Sets the Ollama base URL. Defaults to {@code http://localhost:11434}. */
        public Builder baseUrl(String baseUrl)     { this.baseUrl = baseUrl; return this; }

        /** Sets the model by string ID — use for versioned tags (e.g. {@code "llama3.2:70b"}). */
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }

        /** Sets the model from the {@link Models} catalogue (preferred for base model names). */
        public Builder model(Models model)         { this.modelName = model.id; return this; }

        /** Sampling temperature. Defaults to the model's built-in value when not set. */
        public Builder temperature(double t)       { this.temperature = t; return this; }

        /** HTTP request timeout. Defaults to {@code 5 minutes}. */
        public Builder timeout(Duration timeout)   { this.timeout = timeout; return this; }

        /** Enables LangChain4j request logging to SLF4J. */
        public Builder logRequests(boolean v)      { this.logRequests = v; return this; }

        /** Enables LangChain4j response logging to SLF4J. */
        public Builder logResponses(boolean v)     { this.logResponses = v; return this; }

        /**
         * Sends tools to the model natively instead of leaving them to the strategy's
         * prompt-based routing. Off by default.
         *
         * <p>Opt-in rather than automatic because tool support is a property of the model,
         * not of Ollama, and this client cannot infer it: {@link #modelName(String)} accepts
         * any tag the server happens to host, so there is no name to match on reliably. Turn
         * it on for a model that does support tools — {@code llama3.1} and later,
         * {@code qwen2.5}, {@code mistral-nemo}, {@code mistral} — and leave it off for one
         * that does not, such as {@code llama3}, {@code gemma2}/{@code gemma3} or
         * {@code phi3.5}: enabling it there sends tool specifications the model ignores while
         * {@link LlmClient#supportsNativeTools()} tells the strategy to drop the text
         * scaffolding those models actually rely on, so the agent would stop calling tools
         * altogether.
         */
        public Builder nativeTools(boolean v)      { this.nativeTools = v; return this; }

        /**
         * Builds the {@link OllamaLlmClient}.
         *
         * @throws IllegalArgumentException if {@code baseUrl} or {@code modelName} is blank
         */
        public OllamaLlmClient build() {
            if (baseUrl == null || baseUrl.isBlank())
                throw new IllegalArgumentException("Ollama base URL is required");
            if (modelName == null || modelName.isBlank())
                throw new IllegalArgumentException("Model name is required");
            return new OllamaLlmClient(this);
        }
    }
}
