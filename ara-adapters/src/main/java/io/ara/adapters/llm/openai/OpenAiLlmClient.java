package io.ara.adapters.llm.openai;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.ara.adapters.llm.AbstractLangChain4jLlmClient;
import io.ara.adapters.llm.AbstractLlmClientBuilder;
import io.ara.adapters.llm.AbstractLlmClientBuilder.LlmSettings;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmException;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;

/**
 * {@link LlmClient} adapter for the <a href="https://platform.openai.com/">OpenAI</a> API,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Compatible with any OpenAI-compatible endpoint (Azure OpenAI, LM Studio, Groq, Together AI, …)
 * by overriding the base URL via {@link Builder#baseUrl(String)}.
 *
 * <pre>{@code
 * LlmClient gpt4o = OpenAiLlmClient.builder()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .modelName("gpt-oss-20b")
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("gpt-4o", gpt4o)
 *     .build();
 * }</pre>
 *
 * <h2>Function calling</h2>
 * <p>When {@link LlmCallContext} carries resolved tools, they are converted to LangChain4j
 * {@link ToolSpecification} objects and forwarded to the OpenAI tool-calls API.
 *
 * <h2>OpenAI-compatible endpoints</h2>
 * <pre>{@code
 * // Groq example
 * LlmClient groq = OpenAiLlmClient.builder()
 *     .apiKey(System.getenv("GROQ_API_KEY"))
 *     .baseUrl("https://api.groq.com/openai/v1")
 *     .modelName("llama3-70b-8192")
 *     .build();
 * }</pre>
 *
 * @see LlmClient
 */
public class OpenAiLlmClient extends AbstractLangChain4jLlmClient {

    private static final String PROVIDER = "openai";

    private final OpenAiChatModel chatModel;
    // Lazily initialize the streaming model to avoid unnecessary resource allocation.
    private volatile OpenAiStreamingChatModel streamingModel;
    private final String modelName;
    private final String apiKey;
    private final String baseUrl;
    private final Double defaultTemperature;
    private final Double defaultTopP;
    private final Integer defaultMaxTokens;
    private final Duration timeout;
    private final boolean logRequests;
    private final boolean logResponses;
    private final Map<String, String> customHeaders;
    private final boolean documentSupport;
    /**
     * Forces HTTP/1.1 on <em>both</em> the blocking and the streaming model's underlying JDK
     * {@code HttpClient}.
     *
     * <p>It addresses two distinct failures of OpenAI-compatible endpoints, which is why it
     * must cover both call paths rather than streaming alone:
     * <ul>
     *   <li><b>SSE buffering (streaming only).</b> Some gateways use HTTP/2 multiplexing that
     *       buffers SSE data-frames server-side and delivers them all at once at the end of the
     *       response instead of flushing each token as it arrives. {@code HTTP_1_1} disables
     *       multiplexing so each {@code data:} frame is flushed immediately. See
     *       https://github.com/langchain4j/langchain4j/issues/3682.</li>
     *   <li><b>Upgrade rejection (every request, blocking included).</b> A vLLM served through
     *       uvicorn's {@code httptools} path mistakes the HTTP/2 h2c upgrade handshake the JDK
     *       client may attempt for a WebSocket handshake, rejects it, logs
     *       {@code "Unsupported upgrade request."} and drops the request body — so even a plain
     *       {@code complete()} call fails. Pinning HTTP/1.1 never sends the upgrade header.</li>
     * </ul>
     *
     * <p>Because the second failure hits the blocking path too, the flag is applied to
     * {@code chatModel} as well as the streaming model — see {@link #applyHttp1IfForced}.
     */
    private final boolean forceHttp1;
    /**
     * Precomputed once: the media capability is a pure function of {@link #documentSupport},
     * which never changes after construction, so rebuilding the set on every request only
     * allocates and streams the vocabulary needlessly.
     */
    private final Set<String> supportedMediaTypes;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Enumeration of OpenAI models commonly used through this adapter.
     *
     * <p>Only hosted-OpenAI models belong here; an OpenAI-<em>compatible</em> endpoint
     * (Azure, Groq, LM Studio, Together AI, a corporate gateway) serves its own catalogue, so
     * for those keep using {@link Builder#modelName(String)} with the id the endpoint expects.
     *
     * <p>The {@code o}-series are reasoning models: they reject {@code temperature} values
     * other than the default and expect {@code max_completion_tokens} rather than
     * {@code max_tokens}. Point this adapter at one only if you are prepared to leave
     * {@link Builder#temperature(double)} unset — see the class notes on OpenAI-compatible
     * endpoints.
     *
     * <p>Model ids and context windows are from the
     * <a href="https://platform.openai.com/docs/models">OpenAI models overview</a>
     * (last verified: 2026-10). Context window is the combined input+output token budget.
     */
    public enum Models {
        // GPT-5 family
        GPT_5           ("gpt-5",            400_000),
        GPT_5_MINI      ("gpt-5-mini",       400_000),
        GPT_5_NANO      ("gpt-5-nano",       400_000),
        // GPT-4.1 family (1M-token context)
        GPT_4_1         ("gpt-4.1",        1_000_000),
        GPT_4_1_MINI    ("gpt-4.1-mini",   1_000_000),
        GPT_4_1_NANO    ("gpt-4.1-nano",   1_000_000),
        // GPT-4o family
        GPT_4O          ("gpt-4o",           128_000),
        GPT_4O_MINI     ("gpt-4o-mini",      128_000),
        // o-series reasoning models (see enum javadoc on temperature / max tokens)
        O3              ("o3",               200_000),
        O3_MINI         ("o3-mini",          200_000),
        O1              ("o1",               200_000),
        O1_MINI         ("o1-mini",          128_000);

        /** OpenAI model identifier string. */
        public final String id;
        /** Maximum context window in tokens (combined input + output). */
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id            = id;
            this.contextWindow = contextWindow;
        }
    }

    private OpenAiLlmClient(Builder builder) {
        LlmSettings s = builder.settings();
        this.modelName = s.modelName();
        this.apiKey = s.apiKey();
        this.baseUrl = s.baseUrl();
        this.defaultTemperature = s.temperature();
        this.defaultTopP = builder.topP;
        this.defaultMaxTokens = s.maxTokens();
        this.timeout = s.timeout();
        this.logRequests = s.logRequests();
        this.logResponses = s.logResponses();
        this.customHeaders = builder.customHeaders;
        // Unset by the caller ⇒ derive it: hosted OpenAI (no custom base URL) accepts `file`
        // parts, an arbitrary OpenAI-compatible endpoint usually does not. See
        // supportedMediaTypes().
        this.documentSupport = builder.documentSupport != null
                ? builder.documentSupport
                : (s.baseUrl() == null || s.baseUrl().isBlank());
        this.forceHttp1 = builder.forceHttp1;
        this.supportedMediaTypes = documentSupport
                ? MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT)
                : MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.TEXT);

        this.chatModel = applyHttp1IfForced(OpenAiChatModel.builder()
                .apiKey(s.apiKey())
                .baseUrl(s.baseUrl())
                .modelName(s.modelName())
                .temperature(s.temperature())
                .topP(builder.topP)
                .maxTokens(s.maxTokens())
                .timeout(s.timeout())
                .customHeaders(customHeaders)
                .logRequests(s.logRequests())
                .logResponses(s.logResponses())
                .maxRetries(0))
                .build();
    }

    /**
     * Installs an HTTP/1.1-pinned JDK client on the blocking chat-model builder when
     * {@link #forceHttp1} is set, and returns the builder for chaining. A no-op otherwise, so
     * the default build is byte-for-byte what it was before this flag reached the blocking path.
     *
     * <p>Shared with {@link #getStreamingModel()} through {@link #http1ClientBuilder()}: both
     * the blocking and the streaming model must pin HTTP/1.1 for the same reason — not just the
     * SSE-buffering the streaming case documents, but the {@code Connection: Upgrade} rejection
     * a uvicorn/{@code httptools} vLLM applies to <em>every</em> request, {@code complete()}
     * included. See the {@link #forceHttp1} field javadoc.
     */
    private OpenAiChatModel.OpenAiChatModelBuilder applyHttp1IfForced(
            OpenAiChatModel.OpenAiChatModelBuilder builder) {
        if (forceHttp1) builder.httpClientBuilder(http1ClientBuilder());
        return builder;
    }

    /**
     * A {@link JdkHttpClientBuilder} pinned to HTTP/1.1. Pinning the version removes the h2c
     * upgrade handshake the JDK client would otherwise attempt — the handshake a
     * uvicorn/{@code httptools} vLLM mistakes for a WebSocket upgrade, rejecting it and dropping
     * the request body. One builder shape, used by both the blocking and the streaming model.
     */
    private static JdkHttpClientBuilder http1ClientBuilder() {
        java.net.http.HttpClient.Builder jdkBuilder = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1);
        return JdkHttpClient.builder().httpClientBuilder(jdkBuilder);
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelName;
    }

    /** OpenAI's function-calling is sent natively — see the shared pipeline's response mapping in {@link AbstractLangChain4jLlmClient}. */
    @Override
    public boolean supportsNativeTools() {
        return true;
    }

    /**
     * Images as image parts and text files inlined as text — always; PDFs as {@code file}
     * parts only when this client talks to an endpoint known to accept them.
     *
     * <h4>Why documents are conditional</h4>
     * <p>Media support is not a property of "OpenAI" but of the <em>endpoint</em>. This adapter
     * exists to be pointed at any OpenAI-compatible API (Azure, Groq, LM Studio, vLLM, a
     * corporate gateway), and while essentially all of them accept the {@code image_url} part,
     * many reject the {@code file} part that a PDF becomes — typically with an opaque
     * {@code "Unknown part type: file"} 400 from the proxy. Claiming document support there
     * would defeat the whole point of declaring capabilities: instead of a clear ARA failure
     * naming the type and the provider <em>before</em> the request goes out, the caller gets a
     * provider error they have to reverse-engineer.
     *
     * <p>So the default is derived from configuration rather than assumed: no {@link
     * Builder#baseUrl(String)} means hosted OpenAI, which does accept documents; a custom base
     * URL means an endpoint whose document support is unknown, and unknown is treated as
     * unsupported. A caller who knows better opts in with
     * {@link Builder#documentSupport(boolean)} — the same shape as
     * {@code OllamaLlmClient.nativeTools(boolean)}, and for the same reason: the adapter cannot
     * discover this, and guessing generously is what produces the confusing failure.
     *
     * <p>Declared by category rather than by listing MIME strings, so a type added to
     * {@code MediaTypes} in a category the endpoint already handles is picked up here instead
     * of silently staying unsupported.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return supportedMediaTypes;
    }

    @Override
    protected ChatResponse chat(ChatRequest request) {
        return chatModel.chat(request);
    }

    @Override
    protected void streamChat(ChatRequest request, StreamingChatResponseHandler handler) {
        getStreamingModel().chat(request, handler);
    }

    // Thread-safe lazy initialization using double-checked locking.
    private OpenAiStreamingChatModel getStreamingModel() {
        if (streamingModel == null) {
            synchronized (this) {
                if (streamingModel == null) {
                    OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder smBuilder =
                            OpenAiStreamingChatModel.builder()
                                    .apiKey(apiKey)
                                    .baseUrl(baseUrl)
                                    .modelName(modelName)
                                    .temperature(defaultTemperature)
                                    .topP(defaultTopP)
                                    .maxTokens(defaultMaxTokens)
                                    .timeout(timeout)
                                    .customHeaders(customHeaders)
                                    .logRequests(logRequests)
                                    .logResponses(logResponses);

                    if (forceHttp1) {
                        // Force HTTP/1.1 to prevent gateway-side HTTP/2 buffering from batching
                        // SSE frames, and to drop the h2c upgrade a uvicorn/httptools vLLM
                        // rejects — see the forceHttp1 field javadoc. Same builder as the
                        // blocking model, via http1ClientBuilder().
                        smBuilder.httpClientBuilder(http1ClientBuilder());
                    }

                    streamingModel = smBuilder.build();
                }
            }
        }
        return streamingModel;
    }

    @Override
    protected LlmException mapException(Throwable ex) {
        String msg = errorMessage(ex);
        if (msg.contains("401") || msg.contains("invalid_api_key")) {
            return LlmException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate_limit_exceeded")) {
            return LlmException.rateLimit(PROVIDER, msg);
        }
        if (msg.contains("context_length_exceeded")) {
            return LlmException.contextLengthExceeded(PROVIDER, modelName, msg);
        }

        return fallbackClassify(PROVIDER, msg, ex, timeout);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link OpenAiLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link OpenAiLlmClient}.
     *
     * <p>Shared configuration (API key, base URL, model, temperature, max tokens, timeout,
     * logging) lives in {@link AbstractLlmClientBuilder}; this class adds the knobs that are
     * OpenAI-specific. The only required field is {@link #apiKey(String)}.
     */
    public static final class Builder extends AbstractLlmClientBuilder<Builder> {
        private Double  topP;
        /** Nullable on purpose: null means "derive from baseUrl" — see supportedMediaTypes(). */
        private Boolean documentSupport;
        private boolean forceHttp1 = false;
        private Map<String, String> customHeaders = Map.of();

        /** Sets the model from the {@link Models} catalogue (preferred for hosted OpenAI). */
        public Builder model(Models model)        { this.modelName = model.id; return this; }

        /**
         * Nucleus sampling threshold. Unset by default (OpenAI applies its own default,
         * {@code 1.0}). OpenAI recommends altering either {@code temperature} or
         * {@code topP}, not both.
         */
        public Builder topP(double topP)          { this.topP = topP; return this; }

        /**
         * Extra HTTP headers applied to every request (chat and streaming) sent to the
         * OpenAI-compatible endpoint.
         *
         * <p>Useful for endpoints behind a gateway that keys quota or routing off a
         * header — e.g. opencode's {@code X-Session-ID}. Values should stay stable for
         * the duration of the conversation the client serves.
         */
        public Builder customHeaders(Map<String, String> headers) {
            this.customHeaders = headers != null ? headers : Map.of();
            return this;
        }

        /**
         * Declares whether this endpoint accepts PDFs as {@code file} content parts.
         *
         * <p>Leave it unset unless you have to: the default is hosted OpenAI ⇒ yes, custom
         * {@link #baseUrl(String)} ⇒ no, which is right for almost every deployment. Set it to
         * {@code true} for a proxy or gateway you know forwards {@code file} parts (Azure
         * OpenAI, say), and to {@code false} to refuse documents even on hosted OpenAI.
         *
         * <p>Getting it wrong in the generous direction is what this flag exists to prevent:
         * an endpoint that rejects {@code file} parts answers with an opaque
         * {@code "Unknown part type: file"} 400 instead of ARA naming the unsupported type
         * before the call. Wrong in the strict direction merely refuses a PDF that would have
         * worked, saying so clearly.
         */
        public Builder documentSupport(boolean v) { this.documentSupport = v; return this; }

        /**
         * Forces HTTP/1.1 on both the blocking and the streaming model, preventing HTTP/2
         * SSE buffering (streaming) and the h2c upgrade handshake a uvicorn/{@code httptools}
         * vLLM rejects on every request (blocking included). Set this to {@code true} when the
         * endpoint is behind a proxy or gateway that buffers SSE frames, or a vLLM that logs
         * {@code "Unsupported upgrade request."} and returns an empty/failed completion. See the
         * {@code forceHttp1} field javadoc for the two failures in detail.
         */
        public Builder forceHttp1(boolean v) {
            this.forceHttp1 = v; return this;
        }

        /**
         * Builds the {@link OpenAiLlmClient}.
         *
         * @throws IllegalStateException if {@code apiKey} is null
         */
        public OpenAiLlmClient build() {
            if (apiKey == null) throw new IllegalStateException("OpenAI API key is required");
            return new OpenAiLlmClient(this);
        }
    }
}