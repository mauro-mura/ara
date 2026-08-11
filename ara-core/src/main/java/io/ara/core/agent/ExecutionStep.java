package io.ara.core.agent;

import java.util.Objects;

/**
 * A single step recorded during an agent execution trace.
 *
 * <p>Steps are collected by the {@link ExecutionStrategy} implementation and
 * propagated through {@link ExecutionResult} → {@link AgentResponse} →
 * the REST layer so callers can inspect the full reasoning trace.
 *
 * @param type        the kind of step
 * @param toolId      the tool identifier — non-null only when {@code type == StepType.TOOL_CALL}
 * @param arguments   JSON-serialised arguments — non-null only when {@code type == StepType.TOOL_CALL}
 * @param content     the textual content of this step (LLM output, observation text, final answer)
 * @param iteration   the ReAct iteration index (1-based) in which this step occurred
 */
public record ExecutionStep(
        StepType type,
        String   toolId,
        String   arguments,
        String   content,
        int      iteration
) {

    public ExecutionStep {
        Objects.requireNonNull(type, "type must not be null");
        if (iteration < 0) throw new IllegalArgumentException("iteration must be >= 0, got: " + iteration);
    }

    /** Factory for a thought/reasoning step. */
    public static ExecutionStep thought(String content, int iteration) {
        return new ExecutionStep(StepType.THOUGHT, null, null, content, iteration);
    }

    /** Factory for a tool call step. */
    public static ExecutionStep toolCall(String toolId, String arguments, int iteration) {
        Objects.requireNonNull(toolId, "toolId must not be null for a tool_call step");
        return new ExecutionStep(StepType.TOOL_CALL, toolId, arguments, null, iteration);
    }

    /** Factory for a tool observation step. */
    public static ExecutionStep observation(String content, int iteration) {
        return new ExecutionStep(StepType.OBSERVATION, null, null, content, iteration);
    }

    /** Factory for the final answer step. */
    public static ExecutionStep finalAnswer(String content, int iteration) {
        return new ExecutionStep(StepType.FINAL_ANSWER, null, null, content, iteration);
    }

    /** Factory for a speak step (ReSpAct) — a conversational utterance that does not end the task. */
    public static ExecutionStep speak(String content, int iteration) {
        return new ExecutionStep(StepType.SPEAK, null, null, content, iteration);
    }

    /** Factory for an in-loop self-correction step (ReflAct) — see {@link StepType#REFLECTION}. */
    public static ExecutionStep reflection(String content, int iteration) {
        return new ExecutionStep(StepType.REFLECTION, null, null, content, iteration);
    }
}
