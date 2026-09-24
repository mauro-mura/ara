package io.ara.core.agent;

import io.ara.core.llm.LlmClient;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;

/**
 * Pluggable execution strategy that drives the agent's reasoning loop.
 *
 * <p><b>One call, one complete pass.</b> {@code AgentInstance} invokes {@link #execute}
 * exactly once per task — it does not loop around it. The strategy owns its own internal
 * iteration loop (up to {@code AgentConfig.maxIterations()}) and returns only when it has
 * a final answer or a fatal failure. {@code AgentInstance} reads only
 * {@link ExecutionResult#isSuccess()}; the {@code goalAchieved} flag is informational and
 * no built-in strategy re-enters through {@link ExecutionResult#intermediate}.
 *
 * <p>ARA ships with the following built-in strategies, all located in
 * {@code ara-runtime} and selected by name via {@code AgentConfig.plannerStrategy()}:
 * <ul>
 *   <li>{@code "react"}       — standard Reason+Act loop (default)</li>
 *   <li>{@code "respact"}     — ReAct plus a conversational {@code speak} action</li>
 *   <li>{@code "reflact"}     — ReAct plus in-loop self-correction on tool failures</li>
 *   <li>{@code "plan_execute"}— upfront planning then sequential execution</li>
 *   <li>{@code "reflexion"}   — verbal self-critique, retrying the whole episode</li>
 *   <li>{@code "rag+…"}       — retrieval augmentation wrapping another strategy</li>
 * </ul>
 *
 * <p>A name that matches no registered strategy fails loudly when the agent's first task
 * selects its strategy, rather than falling back silently to {@code "react"}.
 */
public interface ExecutionStrategy {

    /**
     * Runs one full pass of the strategy's reasoning loop for the given task.
     *
     * <p>The strategy may call the LLM multiple times, dispatch tool calls through
     * the registry, and update working memory — all inside this single invocation.
     * It signals completion by returning an {@link ExecutionResult}.
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
