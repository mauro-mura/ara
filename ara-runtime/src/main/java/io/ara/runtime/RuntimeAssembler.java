package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmRouter;
import io.ara.core.memory.MemoryConfig;
import io.ara.core.memory.MemoryManager;
import io.ara.core.retriever.RetrieverRouter;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.agent.InstanceContextStore;
import io.ara.runtime.bus.DelegatingToolRegistry;
import io.ara.runtime.bus.LocalMessageBus;
import io.ara.runtime.config.AraRuntimeConfig;
import io.ara.runtime.factory.AgentFactory;
import io.ara.runtime.factory.DefaultLlmRouter;
import io.ara.runtime.factory.DefaultRetrieverRouter;
import io.ara.runtime.hitl.ApprovalToolRegistry;
import io.ara.runtime.llm.InstrumentedLlmClient;
import io.ara.runtime.memory.EvictionPolicy;
import io.ara.runtime.memory.SlidingWindowMemoryManager;
import io.ara.runtime.scheduler.AgentScheduler;
import io.ara.runtime.scheduler.LocalAgentScheduler;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.strategy.ExecutionPlanner;
import io.ara.runtime.strategy.PlanExecuteStrategy;
import io.ara.runtime.strategy.ReactStrategy;
import io.ara.runtime.strategy.ReflActStrategy;
import io.ara.runtime.strategy.ReflexionStrategy;
import io.ara.runtime.strategy.ReSpActStrategy;
import io.ara.runtime.strategy.RetrievalAugmentedStrategy;
import io.ara.runtime.telemetry.TelemetryToolRegistry;
import io.ara.runtime.wiring.AggregatingToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Validates a configured {@link AraRuntime.Builder} and assembles the runtime object
 * graph from it.
 *
 * <p>Extracted from {@code AraRuntime.Builder} so the builder stays a pure fluent
 * configuration surface and this class owns the assembly steps — resolving defaults,
 * instrumenting clients, registering the built-in strategies, and composing the per-agent
 * tool chain. The two are in the same package, so this reads the builder's fields
 * directly; nothing outside {@code io.ara.runtime} can.
 */
final class RuntimeAssembler {

    private final AraRuntime.Builder b;

    RuntimeAssembler(AraRuntime.Builder builder) {
        this.b = builder;
    }

    AraRuntime assemble() {
        // Validate configuration early to fail fast.
        validate();

        // Resolve core components into separate helpers for readability.
        AraRuntimeConfig cfg = resolveConfig();
        InstanceContextStore ctxStore = resolveInstanceContextStore();
        AgentRegistry registry = new AgentRegistry();
        Function<AgentConfig, MemoryManager> memFactory = resolveMemoryFactory(registry);
        LocalMessageBus messageBus = new LocalMessageBus(registry, b.telemetry, b.approvalGate, b.temporaryScopeRegistry);
        Map<String, LlmClient> instrumentedClients = instrumentClients();
        ExecutionPlanner planner = buildExecutionPlanner(instrumentedClients);
        Map<String, ToolRegistry> perAgentRegistries = new ConcurrentHashMap<>();
        Function<AgentConfig, ToolRegistry> perAgentToolRegistry = resolvePerAgentToolRegistry(perAgentRegistries);
        AgentFactory agentFactory = buildAgentFactory(
                instrumentedClients, planner, perAgentToolRegistry, messageBus, memFactory, registry);
        AgentScheduler scheduler = new LocalAgentScheduler(registry, b.scheduleExecutionListener);
        return new AraRuntime(cfg, agentFactory, registry, b.agentProvider, scheduler, ctxStore,
                b.approvalGate, b.temporaryScopeRegistry, b.abacPolicyEngine,
                Map.copyOf(instrumentedClients), discoveryRegistry(perAgentRegistries),
                Map.copyOf(b.namedRetrievers));
    }

    /** Resolve the runtime configuration, falling back to defaults. */
    private AraRuntimeConfig resolveConfig() {
        return b.runtimeConfig != null ? b.runtimeConfig : AraRuntimeConfig.defaults();
    }

    /** Resolve the instance context store, creating a default one if none supplied. */
    private InstanceContextStore resolveInstanceContextStore() {
        return b.instanceContextStore != null ? b.instanceContextStore : new InstanceContextStore();
    }

    /** Resolve the memory manager factory, using the default if none was set. */
    private Function<AgentConfig, MemoryManager> resolveMemoryFactory(AgentRegistry registry) {
        return b.memoryManagerFactory != null
                ? b.memoryManagerFactory
                : agentCfg -> defaultMemoryManager(agentCfg, registry);
    }

    /**
     * The default {@link MemoryManager} for an agent whose builder never called
     * {@link AraRuntime.Builder#memoryManagerFactory}: {@link InMemoryMemoryManager} (today's
     * behaviour, unlimited window) when {@code agentCfg.memory().workingMemoryTokenBudget()}
     * is 0, or a fully wired {@link SlidingWindowMemoryManager} otherwise (ADR-0086), passing
     * along {@link AraRuntime.Builder#tokenCounter} for its budget accounting (ADR-0087,
     * {@code null} by default). The summariser agent is resolved by id from {@code registry}
     * on every call rather than once, since it may not be registered yet the first time an
     * agent that names it is created — {@code registry} is mutated in place by
     * {@code create(...)} after {@code build()} returns.
     */
    private MemoryManager defaultMemoryManager(AgentConfig agentCfg, AgentRegistry registry) {
        MemoryConfig memory = agentCfg.memory();
        if (memory.workingMemoryTokenBudget() <= 0) {
            return new InMemoryMemoryManager();
        }
        AraAgent summarizer = null;
        String summarizerId = memory.contextSummarizerAgentId();
        if (summarizerId != null && !summarizerId.isBlank()) {
            summarizer = registry.findById(AgentId.of(summarizerId)).orElse(null);
        }
        return new SlidingWindowMemoryManager(
                memory.workingMemoryTokenBudget(),
                EvictionPolicy.from(memory.workingMemoryEviction()),
                summarizer, b.semanticStore, b.embeddingClient, agentCfg.agentId().value(), b.telemetry,
                b.tokenCounter);
    }

    /** Fails fast on configurations {@link #assemble()} could not wire correctly. */
    private void validate() {
        if (b.namedClients.isEmpty()) {
            throw new IllegalStateException(
                    "AraRuntime.Builder: at least one llmClient must be registered");
        }
        if (!b.namedClients.containsKey(b.defaultClientId)) {
            throw new IllegalStateException(
                    "AraRuntime.Builder: default LLM client '" + b.defaultClientId
                            + "' is not among the registered clients " + b.namedClients.keySet()
                            + " — register it via llmClient(id, client) or fix defaultLlmClient(id)");
        }
        if (!b.namedRetrievers.isEmpty() && b.defaultRetrieverId != null
                && !b.namedRetrievers.containsKey(b.defaultRetrieverId)) {
            throw new IllegalStateException(
                    "AraRuntime.Builder: default retriever '" + b.defaultRetrieverId
                            + "' is not among the registered retrievers " + b.namedRetrievers.keySet()
                            + " — register it via retriever(id, retriever) or fix defaultRetriever(id)");
        }
        if (b.retrieverRouter != null && !b.namedRetrievers.isEmpty()) {
            throw new IllegalStateException(
                    "AraRuntime.Builder: set either retriever(...)/retriever(id, ...) or "
                            + "retrieverRouter(...), not both — a custom router supersedes the named map");
        }
        if (b.toolRegistry != null && b.toolRegistryFactory != null) {
            throw new IllegalStateException(
                    "AraRuntime.Builder: set either toolRegistry(...) or toolRegistryFactory(...), not both");
        }
    }

    /** Wraps every registered client so every LLM call — including reflection — is instrumented. */
    private Map<String, LlmClient> instrumentClients() {
        Map<String, LlmClient> instrumentedClients = new LinkedHashMap<>();
        b.namedClients.forEach((id, client) ->
                instrumentedClients.put(id, new InstrumentedLlmClient(client, b.telemetry)));
        return instrumentedClients;
    }

    /**
     * Resolves the tool-registry-per-agent function from whichever of the two mutually
     * exclusive options was set. When {@link AraRuntime.Builder#toolRegistryFactory} is in
     * play, wraps it so every invocation (one per agent, in {@code AgentFactory}) also
     * records its result into {@code perAgentRegistries} — the accumulator
     * {@link #discoveryRegistry} later reads from, since the factory itself is invoked deep
     * inside {@code AgentFactory}, out of {@code AraRuntime}'s direct reach otherwise.
     */
    private Function<AgentConfig, ToolRegistry> resolvePerAgentToolRegistry(
            Map<String, ToolRegistry> perAgentRegistries) {
        if (b.toolRegistryFactory != null) {
            return agentCfg -> {
                ToolRegistry resolved = b.toolRegistryFactory.apply(agentCfg);
                perAgentRegistries.put(agentCfg.agentId().value(), resolved);
                return resolved;
            };
        }
        ToolRegistry baseRegistry = b.toolRegistry != null ? b.toolRegistry : ToolRegistry.empty();
        return agentCfg -> baseRegistry;
    }

    /**
     * Builds the {@link ToolRegistry} exposed via {@link AraRuntime#toolRegistry()} for
     * discovery purposes — see that method's javadoc for the three cases.
     */
    private ToolRegistry discoveryRegistry(Map<String, ToolRegistry> perAgentRegistries) {
        if (b.toolRegistry != null) return b.toolRegistry;
        if (b.toolRegistryFactory != null) return new AggregatingToolRegistry(perAgentRegistries);
        return ToolRegistry.empty();
    }

    /** Registers the built-in strategies (react, respact, plan_execute, reflexion, reflact), RAG variants, and any extras. */
    private ExecutionPlanner buildExecutionPlanner(Map<String, LlmClient> instrumentedClients) {
        LlmRouter reflection = b.reflectionRouter != null
                ? b.reflectionRouter
                : new DefaultLlmRouter(instrumentedClients, b.defaultClientId, b.llmClientFactory);

        ReactStrategy       reactStrategy     = new ReactStrategy();
        ReSpActStrategy     respactStrategy   = new ReSpActStrategy();
        PlanExecuteStrategy planStrategy      = new PlanExecuteStrategy();
        ReflexionStrategy   reflexionStrategy = new ReflexionStrategy(reactStrategy, reflection);
        // Same reflection router as ReflexionStrategy — both support routing the
        // critique call to a different provider than the main loop's own model.
        ReflActStrategy     reflactStrategy   = new ReflActStrategy(reflection);

        ExecutionPlanner.Builder plannerBuilder = ExecutionPlanner.builder()
                .register(reactStrategy)
                .register(respactStrategy)
                .register(planStrategy)
                .register(reflexionStrategy)
                .register(reflactStrategy);

        if (b.retrieverRouter != null || !b.namedRetrievers.isEmpty()) {
            RetrieverRouter rr = b.retrieverRouter != null
                    ? b.retrieverRouter
                    : new DefaultRetrieverRouter(b.namedRetrievers, b.defaultRetrieverId);
            plannerBuilder.register(RetrievalAugmentedStrategy.wrap(reactStrategy,   rr));
            plannerBuilder.register(RetrievalAugmentedStrategy.wrap(respactStrategy, rr));
            plannerBuilder.register(RetrievalAugmentedStrategy.wrap(planStrategy,    rr));
            plannerBuilder.register(RetrievalAugmentedStrategy.wrap(reflactStrategy, rr));
        }

        b.extraStrategies.forEach(plannerBuilder::register);
        return plannerBuilder.build();
    }

    /** Assembles the {@link AgentFactory}: LLM clients, MCP servers, tool registry, and cross-cutting concerns. */
    private AgentFactory buildAgentFactory(
            Map<String, LlmClient> instrumentedClients,
            ExecutionPlanner planner,
            Function<AgentConfig, ToolRegistry> perAgentToolRegistry,
            LocalMessageBus messageBus,
            Function<AgentConfig, MemoryManager> memFactory,
            AgentRegistry registry) {

        AgentFactory.Builder factoryBuilder = AgentFactory.builder()
                .defaultLlmClient(b.defaultClientId);
        // Reuses instrumentedClients (built once in assemble()) — one wrapped instance per
        // registered client, not two. InstrumentedLlmClient adds no overhead beyond an
        // interface dispatch when telemetry is AraTelemetry.noop().
        instrumentedClients.forEach(factoryBuilder::llmClient);
        if (b.llmClientFactory != null) factoryBuilder.llmClientFactory(b.llmClientFactory);
        b.mcpServers.forEach((id, binding) ->
                factoryBuilder.mcpServer(id, binding.connector(), binding.toolsAdapter()));
        if (b.traceStore != null) factoryBuilder.traceEmission(b.traceStore, b.traceBlobStore);   // ADR-0068 D1

        return factoryBuilder
                .toolRegistryFactory(agentCfg ->
                        buildToolChain(agentCfg, perAgentToolRegistry, messageBus))
                .memoryManagerFactory(memFactory)
                .executionPlanner(planner)
                .telemetry(b.telemetry)
                .sessionStore(b.sessionStore)
                .mediaStore(b.mediaStore)
                .interceptors(b.interceptors)
                .registry(registry)
                .build();
    }

    /**
     * Composes the full per-agent tool registry: the resolved base, wrapped for
     * delegation (ADR-0077 D2), then gated for HITL when a gate is configured
     * (ADR-0067 D6), then instrumented for OTel spans.
     *
     * <p>ADR-0077 D2's declared gap, closed: ownGrantedScopes now reflects this
     * agent's own {@code AgentConfig.grantedScopes()} instead of the implicit
     * {@code ScopeSet.EMPTY} every caller got before — the attenuation
     * {@code AgentDelegationTool} already performs (incoming ∩ ownGrantedScopes) had
     * a real ceiling to narrow against only when constructed directly; every agent
     * created through AraRuntime saw EMPTY regardless of what it declared. agentView
     * stays null (unchanged): wiring {@code registry.viewFor(...)} here is a separate
     * decision (ADR-033 Fase 3 §3.3's pre-check), not part of this fix.
     *
     * <p>ADR-0067 D6: insert the approval decorator whenever a gate is configured, and
     * let it decide per call whether a gate is needed (agent flag OR the tool's own
     * {@code ToolSpec.approvalRequired()}) — so a high-risk tool is gated even when
     * the agent's flag is false.
     */
    private ToolRegistry buildToolChain(AgentConfig agentCfg,
                                        Function<AgentConfig, ToolRegistry> perAgentToolRegistry,
                                        LocalMessageBus messageBus) {
        ToolRegistry base = new DelegatingToolRegistry(
                perAgentToolRegistry.apply(agentCfg), messageBus, agentCfg.agentId().value(),
                b.delegationTimeout, agentCfg.delegateStateAccess(), b.sessionStore,
                io.ara.core.auth.ScopeSet.of(agentCfg.grantedScopes()), null);
        ToolRegistry withApproval = b.approvalGate != null
                ? new ApprovalToolRegistry(base, b.approvalGate, agentCfg)
                : base;
        return new TelemetryToolRegistry(withApproval, b.telemetry);
    }
}
