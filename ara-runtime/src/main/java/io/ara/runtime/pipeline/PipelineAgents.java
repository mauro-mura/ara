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
 * {@code AgentInstance}'s full lifecycle for free (per-session isolation, busy policy,
 * cancellation, telemetry, interceptors) instead of re-deriving it, and inherits {@link
 * AgentPipeline#run(AgentTask)}'s own task-propagation guarantees (attachments/context/
 * sessionId/hints reach every step). See the package README, "Why this isn't a
 * hand-rolled AraAgent", for the full rationale — including what a pipeline agent built
 * here composes with — and its "Usage" section for FSM-based and pipeline-in-pipeline
 * examples.
 *
 * <p>Minimal usage:
 * <pre>{@code
 * AraAgent pipelineAgent = PipelineAgents.of(pipeline);
 * AgentResponse response = pipelineAgent.execute(AgentTask.of("Write a report on AI trends"));
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
