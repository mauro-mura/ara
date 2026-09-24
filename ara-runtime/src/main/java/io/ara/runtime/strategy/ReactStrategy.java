package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Implementation of the ReAct (Reason + Act) execution strategy
 * (Yao et al., 2022 — arXiv:2210.03629).
 *
 * <p>Each iteration of the loop follows the cycle:
 * <ol>
 *   <li><strong>Think</strong> — assemble the conversation window from working memory
 *       and call the LLM to reason about the next action.</li>
 *   <li><strong>Act</strong> — if the LLM requested a tool call, dispatch it to the
 *       {@link ToolRegistry} in the appropriate sandbox tier.</li>
 *   <li><strong>Observe</strong> — append the tool result as a new "user" message
 *       so the LLM can incorporate the observation in the next Think step.</li>
 *   <li><strong>Evaluate</strong> — check whether the LLM emitted a {@code FINAL_ANSWER}
 *       token or whether {@code maxIterations} has been reached.</li>
 * </ol>
 *
 * <p>Working memory is expected to have already been seeded with the system prompt
 * and the user's task input by {@code AgentInstance} before this method is called.
 *
 * <p>Tool call JSON is expected in the normalised ARA format:
 * <pre>{@code
 * {"tool_id": "search_web", "arguments": {"query": "capital of France"}}
 * }</pre>
 * LLM provider adapters in {@code ara-llm-providers} are responsible for
 * translating provider-specific formats (OpenAI function calls, Anthropic tool use, …)
 * into this normalised form before populating {@link LlmCompletion#toolCallJson()}.
 *
 * <p>Every piece of this loop besides its top-level shape — the decision logic (final
 * answer vs. tool call), message building, the synthesis nudge, tool dispatch, and
 * streaming — is shared with {@link ReflActStrategy} (identical decision logic, plus
 * in-loop self-correction) and {@link ReSpActStrategy} (a genuinely different three-branch
 * decision, but the same dispatch/streaming machinery). This class itself holds only the
 * strategy-specific decision hook; the loop skeleton lives in {@link ReActLoop}.
 */
public final class ReactStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReactStrategy.class);

    @Override
    public String strategyName() {
        return "react";
    }

    @Override
    public ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config
    ) {
        Objects.requireNonNull(task,   "task must not be null");
        Objects.requireNonNull(llm,    "llm must not be null");
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(tools,  "tools must not be null");
        Objects.requireNonNull(config, "config must not be null");

        return new ReactLoop(task, llm, memory, tools, config).run();
    }

    /**
     * The ReAct-specific pieces over the shared {@link ReActLoop}: the plain two-branch
     * decision (final answer vs. tool call) and the max-iterations message.
     */
    private final class ReactLoop extends ReActLoop {

        ReactLoop(AgentTask task, LlmClient llm, MemoryManager memory, ToolRegistry tools, AgentConfig config) {
            super(task, llm, memory, tools, config);
        }

        @Override
        String systemSuffix() {
            return ReactExecutionSupport.REACT_SYSTEM_SUFFIX;
        }

        @Override
        void logStart() {
            if (log.isDebugEnabled()) {
                // Guarded: SLF4J evaluates arguments eagerly, so the unguarded stream+toList()
                // below would allocate a fresh list on every execution even with DEBUG off.
                log.debug("ReactStrategy starting for task [{}] maxIterations={} tools={}",
                        task.taskId(), config.maxIterations(),
                        resolvedTools.stream().map(AraTool::toolId).toList());
            }
        }

        @Override
        ExecutionResult decide(String output, LlmCompletion completion, boolean forceFinal, int iteration) {
            if (forceFinal) {
                ReactExecutionSupport.ForcedFinalDecision decision =
                        ReactExecutionSupport.decideForcedFinal(output, completion);
                switch (decision) {
                    case ReactExecutionSupport.ForcedFinalDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iteration);
                        steps.add(ExecutionStep.finalAnswer(answer, iteration));
                        return ExecutionResult.success(answer, iteration, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ReactExecutionSupport.ForcedFinalDecision.Continue ignored ->
                        log.debug("Forced-final iteration: skipping tool dispatch for task [{}]", task.taskId());
                }
            } else {
                ReactExecutionSupport.StepDecision decision = ReactExecutionSupport.decideNormal(
                        output, completion, task, resolvedTools.isEmpty());
                switch (decision) {
                    case ReactExecutionSupport.StepDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iteration);
                        steps.add(ExecutionStep.finalAnswer(answer, iteration));
                        return ExecutionResult.success(answer, iteration, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ReactExecutionSupport.StepDecision.DispatchTools(List<ToolCallParser.ToolCallRequest> calls) ->
                        dispatch(calls, completion, iteration);
                    case ReactExecutionSupport.StepDecision.Continue ignored -> {
                        // No tool call and no final answer this iteration — an intermediate
                        // reasoning step. Loop again.
                    }
                }
            }
            return null;
        }

        @Override
        ExecutionResult onMaxIterations() {
            log.warn("Task [{}] hit maxIterations={} without a final answer",
                    task.taskId(), config.maxIterations());
            return ExecutionResult.failure(
                    "Max iterations (%d) reached without a final answer".formatted(config.maxIterations()),
                    iterations, totalPromptTokens, totalOutputTokens, steps);
        }
    }
}
