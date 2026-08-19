package io.ara.adapters.llm.mistral;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import io.ara.adapters.llm.CallParameterUtils;
import io.ara.adapters.llm.ProviderErrorMapper;
import io.ara.adapters.llm.TokenStreamPublisher;
import io.ara.adapters.llm.ToolConversionUtils;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;

/**
 * {@link LlmClient} adapter for the <a href="https://docs.mistral.ai/">Mistral AI</a> API,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Added for its native document handling: Mistral takes a PDF as a document — layout,
 * tables, stamps, scanned pages and all — rather than requiring the text to be extracted
 * upstream first. That is the case ADR-047 exists for, and it is why this adapter is the one
 * the multimodal work brought along.
 *
 * <pre>{@code
 * LlmClient mistral = MistralLlmClient.builder()
 *     .apiKey(System.getenv("MISTRAL_API_KEY"))
 *     .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("mistral", mistral)
 *     .mediaStore(MediaStore.inMemory())
 *     .build();
 * }</pre>
 *
 * <h2>Nothing provider-specific about the media path</h2>
 * <p>Messages go through the same {@code ToolConversionUtils} conversion as every other
 * adapter, which produces langchain4j {@code ImageContent}/{@code PdfFileContent} parts;
 * langchain4j's Mistral mapper turns those into Mistral's own document and image content on
 * the wire. So no code above the adapter — and nothing in this class — differs between
 * sending a PDF to Mistral and sending one to OpenAI.
 *
 * @see LlmClient
 */
public class MistralLlmClient implements LlmClient {

    private static final String PROVIDER = "mistral";

    private final MistralAiChatModel chatModel;
    // Lazily initialised, like the other adapters: an agent that never streams should not pay
    // for a second HTTP client and its connection pool.
    private volatile MistralAiStreamingChatModel streamingModel;
    private final String   modelName;
    private final String   apiKey;
    private final String   baseUrl;
    private final Double   defaultTemperature;
    private final Double   defaultTopP;
    private final Integer  defaultMaxTokens;
    private final Duration timeout;
    private final boolean  logRequests;
    private final boolean  logResponses;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Mistral models this adapter has been used against.
     *
     * <p>{@code documentCapable} records whether the model accepts a PDF as a document, which
     * is not a property of the API but of the model behind it: sending one to a text-only
     * model is a request the API accepts and the model cannot honour. It is informational —
     * {@link #supportedMediaTypes()} reports the adapter's capability, and the model is chosen
     * by whoever builds the client.
     *
     * <p>Model ids and context windows are from the
     * <a href="https://docs.mistral.ai/getting-started/models/models_overview/">Mistral model
     * overview</a> (last verified: 2026-08).
     */
    public enum Models {
        MISTRAL_LARGE_LATEST   ("mistral-large-latest",   128_000, true),
        MISTRAL_MEDIUM_LATEST  ("mistral-medium-latest",  128_000, true),
        MISTRAL_SMALL_LATEST   ("mistral-small-latest",   128_000, true),
        OPEN_MISTRAL_NEMO      ("open-mistral-nemo",      128_000, false),
        CODESTRAL_LATEST       ("codestral-latest",       256_000, false);

        /** Mistral model identifier string. */
        public final String id;
        /** Maximum context window in tokens. */
        public final int contextWindow;
        /** Whether this model can read a PDF as a native document. */
        public final boolean documentCapable;

        Models(String id, int contextWindow, boolean documentCapable) {
            this.id              = id;
            this.contextWindow   = contextWindow;
            this.documentCapable = documentCapable;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private MistralLlmClient(Builder builder) {
        this.modelName          = builder.modelName;
        this.apiKey             = builder.apiKey;
        this.baseUrl            = builder.baseUrl;
        this.defaultTemperature = builder.temperature;
        this.defaultTopP        = builder.topP;
        this.defaultMaxTokens   = builder.maxTokens;
        this.timeout            = builder.timeout;
        this.logRequests        = builder.logRequests;
        this.logResponses       = builder.logResponses;

        this.chatModel = MistralAiChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .topP(builder.topP)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelName;
    }

    /** Mistral's function calling is sent natively — see {@link #complete} and {@link #toLlmCompletion}. */
    @Override
    public boolean supportsNativeTools() {
        return true;
    }

    /**
     * Images, PDFs as native documents, and text files inlined as text — the whole accepted
     * vocabulary. Declared by category rather than as a list of MIME strings, so a type added
     * to {@code MediaTypes} in a category Mistral already handles is picked up here instead of
     * silently staying unsupported.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT);
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        try {
            ChatRequest.Builder reqBuilder = ChatRequest.builder()
                    .messages(toLC4jMessages(messages, context));
            CallParameterUtils.applyTo(reqBuilder, context);

            if (context != null && context.hasResolvedTools()) {
                reqBuilder.toolSpecifications(ToolConversionUtils.toolSpecificationsFor(context));
            }

            return toLlmCompletion(chatModel.chat(reqBuilder.build()));

        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw mapException(ex);
        }
    }

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

                    getStreamingModel().chat(reqBuilder.build(), handler);
                },
                this::mapException);
    }

    // Thread-safe lazy initialisation using double-checked locking, same as the other adapters.
    private MistralAiStreamingChatModel getStreamingModel() {
        if (streamingModel == null) {
            synchronized (this) {
                if (streamingModel == null) {
                    streamingModel = MistralAiStreamingChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .temperature(defaultTemperature)
                            .topP(defaultTopP)
                            .maxTokens(defaultMaxTokens)
                            .timeout(timeout)
                            .logRequests(logRequests)
                            .logResponses(logResponses)
                            .build();
                }
            }
        }
        return streamingModel;
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private List<ChatMessage> toLC4jMessages(List<LlmMessage> messages, LlmCallContext context) {
        // Delegates to ToolConversionUtils so native tool-call/tool-result turns are
        // reconstructed rather than collapsed into a generic UserMessage, and so media is
        // checked against this client's declared types and flattened in one shared place.
        return ToolConversionUtils.toNativeAwareChatMessages(messages, context, this);
    }

    private LlmCompletion toLlmCompletion(ChatResponse response) {
        var ai = response.aiMessage();
        // Both halves matter: aiMessage().text() is null on a tool-call-only turn, and
        // LlmCompletion rejects a null text outright — same shape as the other adapters.
        String text = (ai != null && ai.text() != null) ? ai.text() : "";

        String finishReason = response.finishReason() != null
                ? response.finishReason().toString().toLowerCase() : "stop";
        int inputTokens  = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount()  : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        String toolCallJson = null;
        String toolCallId   = null;
        List<ToolCallEntry> toolCalls = List.of();

        if (ai != null && ai.hasToolExecutionRequests()) {
            // Every request, not just the first: Mistral emits parallel tool calls whenever
            // tools are present, so a multi-call completion is the norm, not the edge case.
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
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("api_key")) {
            return LlmException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("rate_limit")) {
            return LlmException.rateLimit(PROVIDER, msg);
        }
        if (msg.contains("500") || msg.contains("502") || msg.contains("503")) {
            return LlmException.serverError(PROVIDER, msg, 500);
        }
        if (msg.contains("too large") || msg.contains("context") && msg.contains("length")) {
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
     * Creates a new builder for {@link MistralLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link MistralLlmClient}.
     *
     * <p>The only required field is {@link #apiKey(String)}. All other fields have sensible
     * defaults ({@link Models#MISTRAL_MEDIUM_LATEST}, temperature 0.7, 2000 max tokens).
     */
    public static final class Builder {
        private String   apiKey;
        private String   baseUrl;
        private String   modelName    = Models.MISTRAL_MEDIUM_LATEST.id;
        private Double   temperature  = 0.7;
        private Double   topP;
        private Integer  maxTokens    = 2000;
        private Duration timeout      = Duration.ofSeconds(60);
        private boolean  logRequests  = false;
        private boolean  logResponses = false;

        /** Sets the Mistral API key (required). */
        public Builder apiKey(String apiKey)       { this.apiKey = apiKey; return this; }

        /** Overrides the default API base URL (useful for proxies and testing). */
        public Builder baseUrl(String baseUrl)     { this.baseUrl = baseUrl; return this; }

        /** Sets the model from the {@link Models} catalogue (preferred). */
        public Builder model(Models model)         { this.modelName = model.id; return this; }

        /** Sets the model by string ID (use for non-catalogued or preview models). */
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }

        /** Sampling temperature. Defaults to {@code 0.7}. */
        public Builder temperature(double t)       { this.temperature = t; return this; }

        /**
         * Nucleus sampling threshold. Unset by default, so Mistral applies its own — altering
         * either {@code temperature} or {@code topP} is recommended, not both.
         */
        public Builder topP(double topP)           { this.topP = topP; return this; }

        /** Maximum output tokens. Defaults to {@code 2000}. */
        public Builder maxTokens(int maxTokens)    { this.maxTokens = maxTokens; return this; }

        /** HTTP request timeout. Defaults to {@code 60s}. */
        public Builder timeout(Duration timeout)   { this.timeout = timeout; return this; }

        /** Enables LangChain4j request logging to SLF4J. */
        public Builder logRequests(boolean v)      { this.logRequests = v; return this; }

        /** Enables LangChain4j response logging to SLF4J. */
        public Builder logResponses(boolean v)     { this.logResponses = v; return this; }

        /**
         * Builds the {@link MistralLlmClient}.
         *
         * @throws IllegalStateException if {@code apiKey} is null
         */
        public MistralLlmClient build() {
            if (apiKey == null) throw new IllegalStateException("Mistral API key is required");
            return new MistralLlmClient(this);
        }
    }
}
