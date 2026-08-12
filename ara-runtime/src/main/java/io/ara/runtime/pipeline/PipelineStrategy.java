package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmClient;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Adapts an {@link AgentPipeline} to the {@link ExecutionStrategy} contract, so a
 * pipeline can be hosted inside a real {@code AgentInstance} instead of a hand-rolled
 * {@code AraAgent} implementation re-deriving its own, partial copy of the agent
 * lifecycle.
 *
 * <p>Hosting a pipeline this way is free-riding on machinery {@code AgentInstance}
 * already has: per-session isolation and busy policy (ADR-016), cooperative
 * cancellation, the {@code agent.execute} telemetry span, and the interceptor chain
 * all apply to a pipeline exactly as they do to any other agent, with no extra code
 * in this class.
 *
 * <p>Ignores {@code llm}, {@code memory}, and {@code tools} entirely — a pipeline's
 * actual work happens inside its step agents, each of which resolves its own
 * collaborators independently. {@code AgentInstance} still seeds working memory with
 * a system prompt and resolves an {@code LlmClient} before calling {@link #execute}
 * (see {@link PipelineAgents}), but neither is ever read here; harmless bookkeeping,
 * not a correctness concern.
 *
 * <p>Package-private: never registered in a shared, multi-strategy
 * {@code ExecutionPlanner}. {@link PipelineAgents#of} builds a fresh, single-strategy
 * planner dedicated to one pipeline instance, so {@link #strategyName()} only ever
 * needs to be unique within that one planner — see the constructor for why it's
 * configurable rather than a fixed constant.
 */
final class PipelineStrategy implements ExecutionStrategy {

    /** Default strategy name used when the caller doesn't supply an {@link AgentConfig} of their own. */
    static final String DEFAULT_STRATEGY_NAME = "pipeline";

    private final AgentPipeline pipeline;
    private final String        strategyName;

    /**
     * @param pipeline     the pipeline this strategy runs
     * @param strategyName the name to register this strategy under — deliberately NOT
     *                     hardcoded to {@link #DEFAULT_STRATEGY_NAME}: {@link
     *                     PipelineAgents#of(io.ara.core.common.AgentId, AgentConfig, AgentPipeline)}
     *                     accepts an arbitrary caller-supplied {@code AgentConfig}, whose
     *                     {@code plannerStrategy()} may already be set to something else
     *                     (or left at its own default, {@code "react"}). Registering this
     *                     strategy under whatever name that config already carries — rather
     *                     than forcing callers to also set {@code .plannerStrategy("pipeline")}
     *                     — means {@code ExecutionPlanner.select(config)} always finds an
     *                     exact match and never falls through to its own default lookup,
     *                     regardless of what the caller's config contains.
     */
    PipelineStrategy(AgentPipeline pipeline, String strategyName) {
        this.pipeline     = Objects.requireNonNull(pipeline, "pipeline must not be null");
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName must not be null");
    }

    @Override
    public String strategyName() {
        return strategyName;
    }

    @Override
    public ExecutionResult execute(
            AgentTask task, LlmClient llm, MemoryManager memory, ToolRegistry tools, AgentConfig config) {
        PipelineResult result = pipeline.run(task);

        // Aggregated across every executed step's own AgentResponse (PipelineResult.
        // totalInputTokens()/totalOutputTokens()), not just the last one — see
        // PipelineResult's Javadoc. Likewise the execution-step trace is the
        // concatenation of every step's own trace, in execution order.
        int iterations   = result.stepsExecuted().size();
        int promptTokens = result.totalInputTokens();
        int outputTokens = result.totalOutputTokens();
        List<ExecutionStep> steps = result.stepHistory().stream()
                .flatMap(s -> s.response().steps().stream())
                .toList();

        // On failure, finalOutput() still holds the real content of the last step that
        // ran successfully before the pipeline stopped (e.g. a maxSteps cap) — carried
        // through explicitly rather than discarded, so a caller that hit the loop's step
        // cap still gets the best answer produced so far instead of an empty string.
        return result.success()
                ? ExecutionResult.success(result.finalOutput(), iterations, promptTokens, outputTokens, steps)
                : ExecutionResult.failure(result.failureReason(), result.finalOutput(), iterations, promptTokens, outputTokens, steps);
    }
}
