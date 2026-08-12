package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.agent.AgentInstance;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.strategy.ExecutionPlanner;

import java.util.List;
import java.util.Objects;

/**
 * Factory that wraps an {@link AgentPipeline} as an {@link AraAgent}, making pipelines
 * first-class composable units in the ARA ecosystem.
 *
 * <p>The returned agent is a real {@code AgentInstance} hosting a {@link
 * PipelineStrategy} — not a hand-rolled {@code AraAgent} implementation — so it gets
 * {@code AgentInstance}'s full lifecycle for free: per-session isolation and busy
 * policy (ADR-016), cooperative cancellation, the {@code agent.execute} telemetry
 * span, and interceptors. A pipeline agent built here can be:
 * <ul>
 *   <li>registered in {@code AgentRegistry} and discovered like any other agent</li>
 *   <li>used as a node inside an {@code AgentGraph} (requires {@code ara-graph})</li>
 *   <li>delegated to via {@code AgentDelegationTool} by a supervisor LLM</li>
 *   <li>nested inside another pipeline as a step</li>
 *   <li>protected by ADR-033 scope-based authorization</li>
 * </ul>
 *
 * <p>Because pipeline execution goes through {@link AgentPipeline#run(AgentTask)},
 * every step task is derived from the incoming task via {@code withInput(...)} —
 * {@code attachments()}, {@code context()}, {@code sessionId()}, and {@code hints()}
 * survive into every step, not just the initial input string.
 *
 * <p>Usage:
 * <pre>{@code
 * AgentPipeline pipeline = AgentPipeline.fsmBuilder()
 *     .state("draft",  draftAgent)
 *     .state("review", reviewAgent)
 *     .state("done",   doneAgent)
 *     .initial("draft")
 *     .terminal("done")
 *     .transition("draft",  "review")
 *     .transition("review", exec ->
 *         exec.lastOutput().contains("APPROVED") ? "done" : "draft")
 *     .build();
 *
 * AraAgent pipelineAgent = PipelineAgents.of(pipeline);
 * AgentResponse response = pipelineAgent.execute(AgentTask.of("Write a report on AI trends"));
 * }</pre>
 *
 * <p>As a step inside another pipeline:
 * <pre>{@code
 * AraAgent inner = PipelineAgents.of(innerPipeline);
 *
 * AgentPipeline outer = AgentPipeline.builder()
 *     .step("enrich", enrichAgent)
 *     .step("process", inner)      // pipeline-in-pipeline
 *     .step("format",  formatAgent)
 *     .build();
 * }</pre>
 */
public final class PipelineAgents {

    private PipelineAgents() {}

    /**
     * Creates an {@link AraAgent} with an auto-generated id and default config.
     */
    public static AraAgent of(AgentPipeline pipeline) {
        AgentId id = AgentId.generate();
        AgentConfig config = AgentConfig.defaults()
                .agentId(id)
                .agentType("pipeline")
                // Explicit rather than left at AgentConfig's own default ("react") —
                // this config never drives a ReAct loop, and leaving the default in
                // place would make logs/telemetry misleadingly say "selected strategy
                // [react]" for a run that never reasons at all.
                .plannerStrategy(PipelineStrategy.DEFAULT_STRATEGY_NAME)
                .build();
        return of(id, config, pipeline);
    }

    /**
     * Creates an {@link AraAgent} with an explicit id and config.
     *
     * <p>{@code config.plannerStrategy()} is read but never required to be any
     * particular value — the pipeline is hosted behind a dedicated {@link
     * ExecutionPlanner} that registers {@link PipelineStrategy} under whatever name
     * {@code config} already carries, so any config (including one built for another
     * agent type and reused here) resolves correctly with no extra wiring.
     */
    public static AraAgent of(AgentId agentId, AgentConfig config, AgentPipeline pipeline) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(config,  "config must not be null");
        Objects.requireNonNull(pipeline, "pipeline must not be null");

        PipelineStrategy strategy = new PipelineStrategy(pipeline, config.plannerStrategy());
        ExecutionPlanner planner  = ExecutionPlanner.builder().register(strategy).build();

        return new AgentInstance(
                config,
                new NoopLlmClient(),
                sessionId -> new InMemoryMemoryManager(),
                ToolRegistry.empty(),
                planner,
                new AgentInterceptorChain(List.of())
        );
    }

    /**
     * Stand-in {@link LlmClient} required by {@code AgentInstance}'s constructor
     * (it rejects a null client with no router). {@link PipelineStrategy} never calls
     * it — {@code complete} throws rather than silently returning a bogus completion,
     * so a future change that accidentally wires an LLM call into the pipeline path
     * fails loudly instead of producing a confusing empty answer.
     *
     * <p>Package-private (rather than {@code private}) purely so a test in this package
     * can instantiate it directly and assert {@link #complete} actually throws — that
     * behavior is otherwise unreachable through the public pipeline API, since nothing
     * in {@link PipelineStrategy} ever calls it.
     */
    static final class NoopLlmClient implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) throws LlmException {
            throw new UnsupportedOperationException(
                    "PipelineStrategy never calls the LLM client directly — this indicates a bug "
                    + "if reached");
        }

        @Override
        public String providerId() {
            return "pipeline";
        }
    }
}
