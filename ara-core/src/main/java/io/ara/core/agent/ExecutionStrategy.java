package io.ara.core.agent;

import io.ara.core.llm.LlmClient;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;

/**
 * Pluggable execution strategy that drives the agent's reasoning loop.
 *
 * <p>ARA ships with the following built-in strategies, all located in
 * {@code ara-runtime}:
 * <ul>
 *   <li>{@code ReactStrategy}       — standard Reason+Act loop (default)</li>
 *   <li>{@code PlanExecuteStrategy} — upfront planning then sequential execution</li>
 *   <li>{@code ReflexionStrategy}   — verbal reinforcement loop with retry</li>
 * </ul>
 *
 * <p>The strategy is selected by {@code ExecutionPlanner} based on the
 * {@code AgentConfig.plannerStrategy()} field.
 */
public interface ExecutionStrategy {

    /**
     * Runs one full pass of the strategy's reasoning loop for the given task.
     *
     * <p>The strategy may call the LLM multiple times, dispatch tool calls through
     * the registry, and update working memory. It signals completion by returning
     * an {@link ExecutionResult}.
     *
     * @param task     the task to execute
     * @param llm      the LLM client to call for reasoning steps
     * @param memory   the agent's memory manager for context retrieval and storage
     * @param tools    the tool registry for action dispatch
     * @param config   the agent configuration providing limits and parameters
     * @return the result of one strategy pass; never {@code null}
     */
    ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config
    );

    /**
     * Returns the canonical name of this strategy (e.g. {@code "react"}).
     *
     * @return strategy name matching the {@code AgentConfig.plannerStrategy()} field
     */
    String strategyName();
}
