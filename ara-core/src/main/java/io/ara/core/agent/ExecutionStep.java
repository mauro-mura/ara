package io.ara.core.agent;

import io.ara.core.tool.ToolResult;

import java.util.Objects;

/**
 * A single step recorded during an agent execution trace.
 *
 * <p>Steps are collected by the {@link ExecutionStrategy} implementation and
 * propagated through {@link ExecutionResult} → {@link AgentResponse} →
 * the REST layer so callers can inspect the full reasoning trace.
 *
 * @param type        the kind of step
 * @param toolId      the tool identifier — non-null for {@code type == StepType.TOOL_CALL};
 *                    for {@code type == StepType.OBSERVATION} the tool whose execution this
 *                    step observes, when the observation came from a tool (always null for
 *                    non-tool observations, e.g. a workflow node's completion)
 * @param arguments   JSON-serialised arguments — non-null only when {@code type == StepType.TOOL_CALL}
 * @param content     the textual content of this step (LLM output, observation text, final answer)
 * @param success     whether the observed execution succeeded — meaningful only for
 *                    {@code type == StepType.OBSERVATION}; {@code null} where the notion
 *                    does not apply (LLM outputs, workflow node completions)
 * @param iteration   the ReAct iteration index (1-based) in which this step occurred
 */
public record ExecutionStep(
        StepType type,
        String   toolId,
        String   arguments,
        String   content,
        Boolean  success,
        int      iteration
) {

    public ExecutionStep {
        Objects.requireNonNull(type, "type must not be null");
        if (iteration < 0) throw new IllegalArgumentException("iteration must be >= 0, got: " + iteration);
    }

    /** Legacy 5-component canonical constructor for callers (and serialised payloads) predating {@code success}. */
    public ExecutionStep(StepType type, String toolId, String arguments, String content, int iteration) {
        this(type, toolId, arguments, content,
                type == StepType.OBSERVATION ? Boolean.FALSE : null, iteration);
    }

    /** Factory for a thought/reasoning step. */
    public static ExecutionStep thought(String content, int iteration) {
        return new ExecutionStep(StepType.THOUGHT, null, null, content, null, iteration);
    }

    /** Factory for a tool call step. */
    public static ExecutionStep toolCall(String toolId, String arguments, int iteration) {
        Objects.requireNonNull(toolId, "toolId must not be null for a tool_call step");
        return new ExecutionStep(StepType.TOOL_CALL, toolId, arguments, null, null, iteration);
    }

    /** Factory for a tool observation step — the tool's id and outcome ride along with its output. */
    public static ExecutionStep observation(ToolResult result, int iteration) {
        return new ExecutionStep(StepType.OBSERVATION, result.toolId(), null, result.content(),
                result.success(), iteration);
    }

    /** Factory for a tool observation step without a {@link ToolResult} (text-only, unknown outcome). */
    public static ExecutionStep observation(String content, int iteration) {
        return new ExecutionStep(StepType.OBSERVATION, null, null, content, null, iteration);
    }

    /** Factory for the final answer step. */
    public static ExecutionStep finalAnswer(String content, int iteration) {
        return new ExecutionStep(StepType.FINAL_ANSWER, null, null, content, null, iteration);
    }

    /** Factory for a speak step (ReSpAct) — a conversational utterance that does not end the task. */
    public static ExecutionStep speak(String content, int iteration) {
        return new ExecutionStep(StepType.SPEAK, null, null, content, null, iteration);
    }

    /** Factory for an in-loop self-correction step (ReflAct) — see {@link StepType#REFLECTION}. */
    public static ExecutionStep reflection(String content, int iteration) {
        return new ExecutionStep(StepType.REFLECTION, null, null, content, null, iteration);
    }
}
