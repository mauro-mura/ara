package io.ara.adapters.llm.anthropic;

import io.ara.adapters.llm.CallParameterUtils;
import io.ara.adapters.llm.ProviderErrorMapper;
import io.ara.adapters.llm.TokenStreamPublisher;
import io.ara.adapters.llm.ToolConversionUtils;
import io.ara.core.llm.*;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/**
 * {@link LlmClient} adapter for the <a href="https://www.anthropic.com/">Anthropic</a> API,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Supports all current Claude 3 and Claude 4 model families. Create an instance via the
 * static {@link #builder()} and wire it into an {@link io.ara.core.agent.AgentConfig}
 * or {@code io.ara.runtime.AraRuntime}:
 *
 * <pre>{@code
 * LlmClient claude = AnthropicLlmClient.builder()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .model(AnthropicLlmClient.Models.CLAUDE_SONNET_4_6)
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("claude", claude)
 *     .build();
 * }</pre>
 *
 * <h2>Function calling</h2>
 * <p>When {@link LlmCallContext} carries resolved tools, they are converted to
 * LangChain4j {@link ToolSpecification} objects and forwarded to the model's native
 * function-calling API.
 *
 * <h2>Streaming</h2>
 * <p>Tokens are emitted via {@link #stream(List, LlmCallContext)} using a server-sent
 * events connection to the Anthropic streaming endpoint.
 *
 * @see LlmClient
 * @see AnthropicLlmClient.Models
 */
public class AnthropicLlmClient implements LlmClient {

    private static final String PROVIDER = "Anthropic";

    private final AnthropicChatModel          chatModel;
    private final AnthropicStreamingChatModel streamingModel;
    private final String                      modelName;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Enumeration of Anthropic Claude models supported by this adapter.
     *
     * <p>Model IDs and context windows are sourced from the
     * <a href="https://platform.claude.com/docs/en/about-claude/models/overview">Anthropic model overview</a>
     * (last verified: 2026-04).
     */
    public enum Models {
        // Claude 4.x family
        CLAUDE_OPUS_4_8      ("claude-opus-4-8",            200_000),
        CLAUDE_OPUS_4_6      ("claude-opus-4-6",            200_000),
        CLAUDE_SONNET_4_6    ("claude-sonnet-4-6",          200_000),
        CLAUDE_HAIKU_4_5     ("claude-haiku-4-5-20251001",  200_000),
        CLAUDE_OPUS_4        ("claude-opus-4-20250514",     200_000),
        CLAUDE_SONNET_4      ("claude-sonnet-4-20250514",   200_000),
        // Claude 3.x family
        CLAUDE_3_7_SONNET    ("claude-3-7-sonnet-20250219", 200_000),
        CLAUDE_3_5_SONNET    ("claude-3-5-sonnet-20241022", 200_000),
        CLAUDE_3_5_HAIKU     ("claude-3-5-haiku-20241022",  200_000),
        CLAUDE_3_OPUS        ("claude-3-opus-20240229",     200_000),
        CLAUDE_3_HAIKU       ("claude-3-haiku-20240307",    200_000);

        /** Anthropic model identifier string. */
        public final String id;
        /** Maximum context window in tokens. */
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id            = id;
            this.contextWindow = contextWindow;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private AnthropicLlmClient(Builder builder) {
        this.modelName = builder.modelName;
        this.chatModel = AnthropicChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();
        this.streamingModel = AnthropicStreamingChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .build();
    }

    // ── LlmClient ─────────────────────────────────────────────────────────────

    @Override
    public String providerId() {
        return "anthropic-" + modelName;
    }

    /** Anthropic's tool use is sent natively — see {@link #complete} and its response mapping. */
    @Override
    public boolean supportsNativeTools() {
        return true;
    }

    /**
     * Images as image blocks, PDFs as document blocks, and text files inlined as text — the
     * whole accepted vocabulary. Declared by category rather than as a list of MIME strings,
     * so a type added to {@code MediaTypes} in a category Anthropic already handles is picked
     * up here instead of silently staying unsupported.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT);
    }

    /**
     * Sends {@code messages} to the Anthropic Chat API and blocks until a completion arrives.
     *
     * @param messages the conversation history (system → user → assistant turns)
     * @param context  per-call parameters: temperature override, max tokens, tool list, etc.
     * @return the model's completion
     * @throws LlmException on authentication failures, rate limits, context-length violations,
     *                       network errors and unrecoverable server errors
     */
    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        try {
            ChatRequest.Builder reqBuilder = ChatRequest.builder()
                    .messages(toLC4jMessages(messages, context));
            CallParameterUtils.applyTo(reqBuilder, context);

            if (context != null && context.hasResolvedTools()) {
                reqBuilder.toolSpecifications(ToolConversionUtils.toolSpecificationsFor(context));
            }

            ChatResponse response = chatModel.chat(reqBuilder.build());
            return toLlmCompletion(response);

        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw mapException(ex);
        }
    }

    /**
     * Streams tokens from the Anthropic streaming endpoint.
     *
     * <p>Each token is emitted as an individual {@code String} item. The publisher
     * completes normally on {@code STOP} or {@code END_TURN}, and exceptionally on any
     * network or API error.
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

                    if (context != null && context.hasResolvedTools()) {
                        reqBuilder.toolSpecifications(ToolConversionUtils.toolSpecificationsFor(context));
                    }

                    streamingModel.chat(reqBuilder.build(), handler);
                },
                this::mapException);
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private List<ChatMessage> toLC4jMessages(List<LlmMessage> messages, LlmCallContext context) {
        // Delegates to ToolConversionUtils so "assistant_tool_call"/"assistant_tool_calls"/"tool"
        // roles are reconstructed as native AiMessage(toolExecutionRequests)/
        // ToolExecutionResultMessage instead of collapsing into a generic UserMessage — see its
        // javadoc for the previous bug this replaced — and so media is checked against this
        // client's declared types and flattened in one shared place.
        return ToolConversionUtils.toNativeAwareChatMessages(messages, context, this);
    }

    private LlmCompletion toLlmCompletion(ChatResponse response) {
        var ai = response.aiMessage();

        String text = (ai != null && ai.text() != null) ? ai.text() : "";

        String finishReason = response.finishReason() != null
                ? response.finishReason().toString().toLowerCase() : "stop";
        int inputTokens  = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount()  : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        String toolCallJson = null;
        String toolCallId   = null;
        List<ToolCallEntry> toolCalls = List.of();
        if (ai != null && ai.hasToolExecutionRequests()) {
            // Map ALL requests: Claude can emit several tool_use blocks in one response.
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
        if (msg.contains("401") || msg.contains("authentication") || msg.contains("api_key")) {
            return LlmException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate_limit") || msg.contains("rate limit")) {
            return LlmException.rateLimit(PROVIDER, msg);
        }
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("overloaded")) {
            return LlmException.serverError(PROVIDER, msg, 500);
        }
        if (msg.contains("context_length") || msg.contains("too long") || msg.contains("max_tokens")) {
            return LlmException.contextLengthExceeded(PROVIDER, modelName, 0, 0);
        }

        // Before falling through to a retryable network error: langchain4j classifies HTTP
        // failures onto its own retriable/non-retriable hierarchy, and reading that is both
        // more accurate than the substring checks above and immune to a provider rewording
        // its error bodies. Without it a malformed request (400) was reported as a network
        // error — retryable — so the strategy retried it and every fallback in a failover
        // pool was tried in turn, for a request that could not succeed on any of them.
        LlmException typed = ProviderErrorMapper.fromTypedException(PROVIDER, ex);
        if (typed != null) return typed;
        return LlmException.networkError(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link AnthropicLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link AnthropicLlmClient}.
     *
     * <p>The only required field is {@link #apiKey(String)}. All other fields have
     * sensible defaults ({@link Models#CLAUDE_SONNET_4_6}, temperature 0.7, 4096 max tokens).
     */
    public static final class Builder {
        private String   apiKey;
        private String   baseUrl;
        private String   modelName   = Models.CLAUDE_SONNET_4_6.id;
        private Double   temperature = 0.7;
        private Integer  maxTokens   = 4096;
        private Duration timeout     = Duration.ofSeconds(60);
        private boolean  logRequests = false;
        private boolean  logResponses= false;

        /** Sets the Anthropic API key (required). */
        public Builder apiKey(String apiKey)       { this.apiKey = apiKey; return this; }

        /** Overrides the default API base URL (useful for proxies and testing). */
        public Builder baseUrl(String baseUrl)     { this.baseUrl = baseUrl; return this; }

        /** Sets the model by string ID (use for non-catalogued or preview models). */
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }

        /** Sets the model from the {@link Models} catalogue (preferred). */
        public Builder model(Models model)         { this.modelName = model.id; return this; }

        /** Sampling temperature. Defaults to {@code 0.7}. */
        public Builder temperature(double t)       { this.temperature = t; return this; }

        /** Maximum output tokens. Defaults to {@code 4096}. */
        public Builder maxTokens(int maxTokens)    { this.maxTokens = maxTokens; return this; }

        /** HTTP request timeout. Defaults to {@code 60s}. */
        public Builder timeout(Duration timeout)   { this.timeout = timeout; return this; }

        /** Enables LangChain4j request logging to SLF4J. */
        public Builder logRequests(boolean v)      { this.logRequests = v; return this; }

        /** Enables LangChain4j response logging to SLF4J. */
        public Builder logResponses(boolean v)     { this.logResponses = v; return this; }

        /**
         * Builds the {@link AnthropicLlmClient}.
         *
         * @throws IllegalArgumentException if {@code apiKey} is null or blank
         */
        public AnthropicLlmClient build() {
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalArgumentException("Anthropic API key is required");
            return new AnthropicLlmClient(this);
        }
    }
}
