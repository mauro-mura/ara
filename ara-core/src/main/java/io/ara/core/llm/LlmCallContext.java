package io.ara.core.llm;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.media.MediaResolver;
import io.ara.core.tool.AraTool;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic per-call parameters for the LLM model.
 *
 * <p>Separates parameters that change at runtime (output schema, per-step temperature,
 * stop sequences, seed) from the static per-agent configuration ({@link AgentConfig}).
 *
 * <p>Built by the strategy for each invocation via {@link #of(AgentConfig, AgentTask)};
 * never persisted or reused across tasks. Immutable — the {@code with*()} methods create new instances.
 *
 * <h2>Priority levels</h2>
 * <ol>
 *   <li>Explicit values on {@code LlmCallContext} (per-call — highest priority)</li>
 *   <li>Values on {@code AgentConfig} (per-agent)</li>
 *   <li>Model defaults (lowest priority)</li>
 * </ol>
 *
 * <p><b>{@code temperature}/{@code topP} are nullable end-to-end</b> ({@code
 * LlmProfile} → {@code AgentConfig} → here) specifically so level 3 above is real: if
 * neither a per-call override nor an {@code AgentConfig} value was ever set, {@link
 * #temperature()}/{@link #topP()} return {@code null}, and an adapter is expected to
 * leave its own client-level default alone rather than substitute some other baked-in
 * "default" value of its own (which would silently defeat whatever the caller
 * configured directly on the {@code LlmClient}).
 */
public final class LlmCallContext {

    // ── Parameters derived from AgentConfig ───────────────────────────────────

    /**
     * The calling agent's id ({@code AgentId.value()}), or {@code null} when the context was
     * built without an {@link AgentConfig}. Like {@link #sessionId()} it is carried for
     * identification only and is never a sampling parameter: no adapter should branch on it.
     *
     * <p>It exists because {@link #agentType()} does not identify anything — its default is the
     * constant {@code "generic"} — so a test double that must answer differently per agent (a
     * workflow with several nodes in flight) has no other way to tell the callers apart.
     */
    private final String       agentId;      // nullable
    private final String       agentType;
    private final int          maxOutputTokens;
    private final Double       temperature;   // nullable — see class javadoc
    private final Double       topP;          // nullable — see class javadoc

    // ── Per-call parameters ───────────────────────────────────────────────────

    /** JSON Schema (draft-07). If non-null the client uses response_format: json_schema. */
    private final String  outputJsonSchema;     // nullable
    /** Schema name for the OpenAI API. Defaults to "output". */
    private final String  outputSchemaName;
    /** Strict mode: exact logit masking. */
    private final boolean strictSchema;
    /** Temperature override for this call. Null = use AgentConfig.temperature(). */
    private final Double  temperatureOverride;  // nullable
    /** Stop sequences for this call. */
    private final List<String> stopSequences;
    /** Seed for reproducibility. Null = non-deterministic sampling. */
    private final Integer seed;                 // nullable
    /** LLM provider override for this call. Null = use AgentConfig.llmProvider(). */
    private final String  llmProviderOverride;  // nullable
    /** Enables INFO-level logging of LLM request/response. Propagated from AgentConfig.logLlmIo(). */
    private final boolean logLlmIo;
    /** Max characters per message in the log. 0 = no truncation. */
    private final int     logLlmIoMaxChars;
    /** If true, the client uses native response_format: json_schema instead of the textual fallback. */
    private final boolean nativeJsonSchema;
    /**
     * Tools already resolved by the strategy for this execution step.
     * When non-null, {@link io.ara.core.llm.LlmClient} must use these
     * instead of resolving them on its own from its registry.
     *
     * <p><b>Architectural note:</b> tool availability is conceptually a different concern
     * from the sampling parameters above (temperature, schema, stop sequences) — it lives
     * here rather than as a separate {@code LlmClient.complete(...)} parameter because every
     * adapter (OpenAI, Anthropic, Ollama) already resolves tool specs off of the call context
     * it receives, and {@link AraTool} is itself a {@code ara-core} domain type, so this is not
     * a module-boundary violation, just a widening of this class's responsibility. Splitting it
     * out into a dedicated parameter would be the cleaner long-term shape, but it means changing
     * the primary {@code LlmClient.complete} signature — worth doing together with the next
     * change that already touches every adapter, not in isolation.
     */
    private final List<AraTool> resolvedTools;

    /**
     * How the adapter turns a {@code MediaRef} on an outgoing message back into bytes.
     * Never {@code null} — {@link MediaResolver#none()} is the default, and it answers by
     * failing with the reference's name and id rather than by producing empty bytes.
     *
     * <p><b>Second occurrence of the same widening, declared as such.</b> Like
     * {@code resolvedTools} above, this is not a sampling parameter, and the cleaner shape
     * is a dedicated argument on {@code LlmClient.complete(...)}. The change that finally
     * touches every adapter — this one — is also the change that already carries a breaking
     * modification to a public record ({@code LlmMessage}), and stacking a signature change
     * to the primary {@code LlmClient} method on top would make two independent migrations
     * land on the same twelve call sites at once. So it is deferred again, on purpose. If a
     * third occurrence shows up, that is the one that should trigger the refactor rather
     * than a third note here.
     */
    private final MediaResolver mediaResolver;

    /**
     * The originating task's session id ({@code SessionId.value()}), or {@code null} for
     * ephemeral tasks with no session. Carried through purely for observability — e.g.
     * {@code InstrumentedLlmClient} tags the {@code llm.complete} span with it — never
     * read by any {@code LlmClient} adapter.
     */
    private final String sessionId;   // nullable

    /**
     * Optional sink the streaming path can use to hand the caller the terminal {@link
     * LlmCompletion} alongside the token stream. A {@code Publisher<String>} can carry
     * only text tokens, so a streaming response that contains tool calls (native tool-call
     * deltas) but no text reaches the subscriber as an empty token sequence and the
     * metadata is lost — the caller then has no way to know the stream actually produced
     * one. When this sink is non-null an adapter that supports it fills it from its
     * terminal response; the caller reads it instead of re-issuing the whole call in
     * blocking mode. {@code null} (the default) means "not interested": adapters skip it
     * and behaviour is exactly as before.
     */
    private final java.util.function.Consumer<LlmCompletion> completionSink;   // nullable

    /**
     * The reasoning options of the calling agent's profile, carried per call because clients are
     * shared by every agent that uses a transport while these options belong to one agent. All three
     * are {@code null} unless the profile set them, and a {@code null} means "say nothing to the
     * provider"; an adapter that cannot apply one that is set rejects the call.
     */
    private final ReasoningEffort reasoningEffort;        // nullable
    private final Integer         thinkingBudgetTokens;   // nullable
    private final Boolean         returnReasoning;        // nullable

    /**
     * Cheap replay of {@code source}'s fields that replaces only the media resolver.
     *
     * <p>Unlike {@link #toBuilder()}{@code +build()} — which rebuilds and re-{@code
     * List.copyOf}s {@code stopSequences}/{@code resolvedTools} — this shares those
     * already-immutable references. {@code withMediaResolver} runs on every LLM call
     * through {@code MediaResolvingLlmClient}, so the two defensive copies would
     * otherwise be paid per call on the hot path.
     */
    private LlmCallContext(LlmCallContext source, MediaResolver mediaResolver) {
        this.agentId             = source.agentId;
        this.agentType           = source.agentType;
        this.maxOutputTokens     = source.maxOutputTokens;
        this.temperature         = source.temperature;
        this.topP                = source.topP;
        this.outputJsonSchema    = source.outputJsonSchema;
        this.outputSchemaName    = source.outputSchemaName;
        this.strictSchema        = source.strictSchema;
        this.temperatureOverride = source.temperatureOverride;
        this.stopSequences       = source.stopSequences;
        this.seed                = source.seed;
        this.llmProviderOverride = source.llmProviderOverride;
        this.logLlmIo            = source.logLlmIo;
        this.logLlmIoMaxChars    = source.logLlmIoMaxChars;
        this.nativeJsonSchema    = source.nativeJsonSchema;
        this.resolvedTools       = source.resolvedTools;
        this.mediaResolver       = mediaResolver;
        this.sessionId           = source.sessionId;
        this.completionSink      = source.completionSink;
        this.reasoningEffort     = source.reasoningEffort;
        this.thinkingBudgetTokens = source.thinkingBudgetTokens;
        this.returnReasoning     = source.returnReasoning;
    }

    private LlmCallContext(Builder b) {
        this.agentId             = b.agentId;
        this.agentType           = b.agentType;
        this.maxOutputTokens     = b.maxOutputTokens;
        this.temperature         = b.temperature;
        this.topP                = b.topP;
        this.outputJsonSchema    = b.outputJsonSchema;
        this.outputSchemaName    = b.outputSchemaName != null ? b.outputSchemaName : "output";
        this.strictSchema        = b.strictSchema;
        this.temperatureOverride = b.temperatureOverride;
        this.stopSequences       = List.copyOf(b.stopSequences);
        this.seed                = b.seed;
        this.llmProviderOverride = b.llmProviderOverride;
        this.logLlmIo            = b.logLlmIo;
        this.logLlmIoMaxChars    = b.logLlmIoMaxChars;
        this.nativeJsonSchema    = b.nativeJsonSchema;
        this.resolvedTools       = b.resolvedTools != null ? List.copyOf(b.resolvedTools) : null;
        this.mediaResolver       = b.mediaResolver != null ? b.mediaResolver : MediaResolver.none();
        this.sessionId           = b.sessionId;
        this.completionSink      = b.completionSink;
        this.reasoningEffort     = b.reasoningEffort;
        this.thinkingBudgetTokens = b.thinkingBudgetTokens;
        this.returnReasoning     = b.returnReasoning;
    }

    /**
     * Builds an {@code LlmCallContext} from the static values in {@code AgentConfig}.
     * All per-call parameters are null/empty.
     */
    public static LlmCallContext from(AgentConfig config) {
        return new Builder()
                .agentId(config.agentId() != null ? config.agentId().value() : null)
                .agentType(config.agentType())
                .maxOutputTokens(config.maxTokensPerStep())
                .temperature(config.temperature())
                .topP(config.topP())
                .logLlmIo(config.logLlmIo())
                .logLlmIoMaxChars(config.logLlmIoMaxChars())
                .nativeJsonSchema(config.nativeJsonSchema())
                .reasoningEffort(config.reasoningEffort())
                .thinkingBudgetTokens(config.thinkingBudgetTokens())
                .returnReasoning(config.returnReasoning())
                .build();
    }

    /**
     * Builds an {@code LlmCallContext} from {@link AgentConfig} + the task's
     * {@link LlmExecutionHints} and session id. Used by the strategy at the start of
     * every {@code execute()}.
     */
    public static LlmCallContext of(AgentConfig config, AgentTask task) {
        // sessionId is not a hint (it is not an LLM sampling parameter) — set it directly
        // from the task, independently of whether LlmExecutionHints is present below.
        LlmCallContext ctx = from(config)
                .toBuilder()
                .sessionId(task.sessionId() != null ? task.sessionId().value() : null)
                .build();

        LlmExecutionHints hints = task.hints();
        if (hints == null) return ctx;

        if (hints.hasOutputSchema()) {
            ctx = ctx.withOutputSchema(
                    hints.outputJsonSchema(),
                    hints.outputSchemaName() != null ? hints.outputSchemaName() : "output",
                    hints.strictSchema());
        }
        if (hints.temperatureOverride() != null) {
            ctx = ctx.withTemperature(hints.temperatureOverride());
        }
        if (hints.hasStopSequences()) {
            ctx = ctx.withStopSequences(hints.stopSequences().toArray(String[]::new));
        }
        if (hints.hasSeed()) {
            ctx = ctx.withSeed(hints.seed());
        }
        if (hints.hasProviderOverride()) {
            ctx = ctx.withProviderOverride(hints.llmProviderOverride());
        }
        return ctx;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public String       agentId()             { return agentId; }
    public String       agentType()           { return agentType; }
    public int          maxOutputTokens()     { return maxOutputTokens; }
    /**
     * Returns the effective temperature: the per-call override if present, else the
     * {@code AgentConfig} value, else {@code null} if neither was ever set — meaning
     * the caller should leave the client's own default temperature alone.
     */
    public Double        temperature()         { return temperatureOverride != null ? temperatureOverride : temperature; }
    public Double        baseTemperature()     { return temperature; }
    /** The effective topP, or {@code null} if never set — see {@link #temperature()}. */
    public Double         topP()               { return topP; }
    public String       outputJsonSchema()    { return outputJsonSchema; }
    public String       outputSchemaName()    { return outputSchemaName; }
    public boolean      strictSchema()        { return strictSchema; }
    public Double       temperatureOverride() { return temperatureOverride; }
    public List<String> stopSequences()       { return stopSequences; }
    public Integer      seed()               { return seed; }
    public String       llmProviderOverride() { return llmProviderOverride; }
    public boolean      logLlmIo()            { return logLlmIo; }
    public int          logLlmIoMaxChars()    { return logLlmIoMaxChars; }
    public boolean      nativeJsonSchema()    { return nativeJsonSchema; }

    public List<AraTool> resolvedTools()       { return resolvedTools; }
    public boolean hasResolvedTools()         { return resolvedTools != null; }

    /** How to fetch the bytes of a {@code MediaRef}; never {@code null} — see the field javadoc. */
    public MediaResolver mediaResolver()      { return mediaResolver; }
    /** {@code true} when a resolver was set deliberately, rather than defaulted to {@code none()}. */
    public boolean hasMediaResolver()         { return mediaResolver != MediaResolver.none(); }

    public boolean hasOutputSchema()          { return outputJsonSchema != null; }
    public boolean hasStopSequences()         { return !stopSequences.isEmpty(); }
    public boolean hasSeed()                  { return seed != null; }
    public boolean hasProviderOverride()      { return llmProviderOverride != null; }

    /** The originating task's session id, or {@code null} if the task had none. */
    public String  sessionId()                { return sessionId; }
    public boolean hasSessionId()             { return sessionId != null; }

    /** The streaming completion sink, or {@code null} when the caller did not ask for one. */
    public java.util.function.Consumer<LlmCompletion> completionSink() { return completionSink; }
    public boolean hasCompletionSink()        { return completionSink != null; }

    public ReasoningEffort reasoningEffort()      { return reasoningEffort; }
    public Integer         thinkingBudgetTokens() { return thinkingBudgetTokens; }
    public Boolean         returnReasoning()      { return returnReasoning; }

    /** Whether the profile set any reasoning option, i.e. whether the call must be built differently. */
    public boolean hasReasoningOptions() {
        return reasoningEffort != null || thinkingBudgetTokens != null || Boolean.TRUE.equals(returnReasoning);
    }

    // ── with*() methods ───────────────────────────────────────────────────────

    public LlmCallContext withOutputSchema(String schema, String name, boolean strict) {
        return toBuilder().outputJsonSchema(schema).outputSchemaName(name).strictSchema(strict).build();
    }

    public LlmCallContext withTemperature(double t) {
        return toBuilder().temperatureOverride(t).build();
    }

    public LlmCallContext withStopSequences(String... stops) {
        return toBuilder().stopSequences(List.of(stops)).build();
    }

    public LlmCallContext withSeed(int seed) {
        return toBuilder().seed(seed).build();
    }

    public LlmCallContext withProviderOverride(String provider) {
        return toBuilder().llmProviderOverride(provider).build();
    }

    public LlmCallContext withResolvedTools(List<AraTool> tools) {
        return toBuilder().resolvedTools(tools).build();
    }

    public LlmCallContext withMediaResolver(MediaResolver resolver) {
        return new LlmCallContext(this, resolver != null ? resolver : MediaResolver.none());
    }

    /**
     * Attaches (or removes, with {@code null}) the streaming completion sink — see the
     * field javadoc. Adapters that support it publish their terminal completion into it;
     * adapters that do not simply never call it.
     */
    public LlmCallContext withCompletionSink(java.util.function.Consumer<LlmCompletion> sink) {
        return toBuilder().completionSink(sink).build();
    }

    private Builder toBuilder() {
        Builder b = new Builder();
        b.agentId             = this.agentId;
        b.agentType           = this.agentType;
        b.maxOutputTokens     = this.maxOutputTokens;
        b.temperature         = this.temperature;
        b.topP                = this.topP;
        b.outputJsonSchema    = this.outputJsonSchema;
        b.outputSchemaName    = this.outputSchemaName;
        b.strictSchema        = this.strictSchema;
        b.temperatureOverride = this.temperatureOverride;
        b.stopSequences       = new ArrayList<>(this.stopSequences);
        b.seed                = this.seed;
        b.llmProviderOverride = this.llmProviderOverride;
        b.logLlmIo            = this.logLlmIo;
        b.logLlmIoMaxChars    = this.logLlmIoMaxChars;
        b.nativeJsonSchema    = this.nativeJsonSchema;
        b.resolvedTools       = this.resolvedTools != null ? new ArrayList<>(this.resolvedTools) : null;
        b.mediaResolver       = this.mediaResolver;
        b.sessionId           = this.sessionId;
        b.completionSink      = this.completionSink;
        b.reasoningEffort     = this.reasoningEffort;
        b.thinkingBudgetTokens = this.thinkingBudgetTokens;
        b.returnReasoning     = this.returnReasoning;
        return b;
    }

    public static final class Builder {
        private String       agentId;
        private String       agentType;
        private int          maxOutputTokens  = 2048;
        private Double        temperature;   // nullable — see class javadoc
        private Double        topP;          // nullable — see class javadoc
        private String       outputJsonSchema;
        private String       outputSchemaName;
        private boolean      strictSchema     = false;
        private Double       temperatureOverride;
        private List<String> stopSequences    = List.of();
        private Integer      seed;
        private String       llmProviderOverride;
        private boolean      logLlmIo         = false;
        private int          logLlmIoMaxChars  = 500;
        private boolean      nativeJsonSchema  = true;
        private List<AraTool> resolvedTools;
        private MediaResolver mediaResolver;
        private String       sessionId;
        private java.util.function.Consumer<LlmCompletion> completionSink;
        private ReasoningEffort reasoningEffort;
        private Integer         thinkingBudgetTokens;
        private Boolean         returnReasoning;

        public Builder agentId(String v)             { this.agentId = v;             return this; }
        public Builder agentType(String v)           { this.agentType = v;           return this; }
        public Builder maxOutputTokens(int v)        { this.maxOutputTokens = v;    return this; }
        public Builder temperature(Double v)         { this.temperature = v;         return this; }
        public Builder topP(Double v)                { this.topP = v;                return this; }
        public Builder outputJsonSchema(String v)    { this.outputJsonSchema = v;    return this; }
        public Builder outputSchemaName(String v)    { this.outputSchemaName = v;    return this; }
        public Builder strictSchema(boolean v)       { this.strictSchema = v;        return this; }
        public Builder temperatureOverride(Double v) { this.temperatureOverride = v; return this; }
        public Builder stopSequences(List<String> v) { this.stopSequences = v;       return this; }
        public Builder seed(Integer v)               { this.seed = v;                return this; }
        public Builder llmProviderOverride(String v) { this.llmProviderOverride = v; return this; }
        public Builder logLlmIo(boolean v)            { this.logLlmIo = v;            return this; }
        public Builder logLlmIoMaxChars(int v)        { this.logLlmIoMaxChars = v;    return this; }
        public Builder nativeJsonSchema(boolean v)    { this.nativeJsonSchema = v;    return this; }
        public Builder resolvedTools(List<AraTool> v) { this.resolvedTools = v;       return this; }
        public Builder mediaResolver(MediaResolver v) { this.mediaResolver = v;       return this; }
        public Builder sessionId(String v)            { this.sessionId = v;           return this; }
        public Builder completionSink(java.util.function.Consumer<LlmCompletion> v) { this.completionSink = v; return this; }
        public Builder reasoningEffort(ReasoningEffort v)    { this.reasoningEffort = v;     return this; }
        public Builder thinkingBudgetTokens(Integer v)       { this.thinkingBudgetTokens = v; return this; }
        public Builder returnReasoning(Boolean v)            { this.returnReasoning = v;     return this; }

        public LlmCallContext build() { return new LlmCallContext(this); }
    }
}
