package io.ara.adapters.llm.ollama;

import io.ara.adapters.llm.ToolConversionUtils;
import io.ara.core.llm.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
 * <p>Most Ollama models do <em>not</em> support native function calling.
 * {@link LlmCallContext#hasResolvedTools()} is therefore ignored. Use prompt-based tool routing
 * ({@link io.ara.core.llm.LlmSelectionPolicy}) when tools are required.
 *
 * @see LlmClient
 * @see OllamaLlmClient.Models
 */
public class OllamaLlmClient implements LlmClient {

    private static final String PROVIDER = "Ollama";

    private final OllamaChatModel          chatModel;
    private final OllamaStreamingChatModel streamingModel;
    private final String                   modelName;

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

    /**
     * Sends {@code messages} to the local Ollama instance and blocks until completion.
     *
     * <p>Tools in {@link LlmCallContext} are ignored (most Ollama models do not support
     * native function calling).
     *
     * @param messages the conversation history (system → user → assistant turns)
     * @param context  per-call parameters (temperature, max tokens)
     * @return the model's completion
     * @throws LlmException on network errors or unexpected Ollama server failures
     */
    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(toLC4jMessages(messages))
                    .build();

            ChatResponse response = chatModel.chat(request);
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
        return subscriber -> {
            CompletableFuture<Void> done = new CompletableFuture<>();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* push-based */ }
                @Override public void cancel() { done.cancel(true); }
            });

            ChatRequest request = ChatRequest.builder()
                    .messages(toLC4jMessages(messages))
                    .build();

            streamingModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    subscriber.onNext(token);
                }
                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    subscriber.onComplete();
                    done.complete(null);
                }
                @Override
                public void onError(Throwable error) {
                    subscriber.onError(mapException(error));
                    done.completeExceptionally(error);
                }
            });
        };
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private List<ChatMessage> toLC4jMessages(List<LlmMessage> messages) {
        // Ollama itself never emits native tool calls (see class javadoc — tools in
        // LlmCallContext are ignored), but a session using ROUND_ROBIN/FAILOVER across
        // providers can still hand this client a history that contains an earlier turn's
        // native tool-call/tool-result entries (e.g. an OpenAI/Anthropic call answered
        // first). Delegating here keeps that history intact instead of degrading it to a
        // confusing generic user turn — see ToolConversionUtils.toNativeAwareChatMessage.
        return messages.stream()
                .map(ToolConversionUtils::toNativeAwareChatMessage)
                .collect(Collectors.toList());
    }

    private LlmCompletion toLlmCompletion(ChatResponse response) {
        String text = response.aiMessage() != null ? response.aiMessage().text() : "";
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
        return new LlmCompletion(text, inputTokens, outputTokens, finishReason, null);
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
