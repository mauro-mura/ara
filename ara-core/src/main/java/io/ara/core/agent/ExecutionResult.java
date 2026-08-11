package io.ara.core.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one pass of an {@link ExecutionStrategy}.
 *
 * <p>A strategy pass may or may not produce a final answer. If the goal was
 * not yet reached ({@code goalAchieved == false}), the {@code AgentInstance}
 * will invoke the strategy again (up to {@code AgentConfig.maxIterations()}).
 *
 * @param goalAchieved   {@code true} when the strategy believes the task is complete
 * @param output         the text produced in this pass (partial or final)
 * @param iterationsDone number of internal iterations the strategy consumed
 * @param promptTokens   prompt/input tokens consumed across all LLM calls in this pass
 * @param outputTokens   completion/output tokens consumed across all LLM calls in this pass
 * @param failureReason  non-null only when the strategy encountered a fatal error
 * @param steps          ordered list of execution steps (thoughts, tool calls, observations)
 */
public record ExecutionResult(
        boolean goalAchieved,
        String output,
        int iterationsDone,
        int promptTokens,
        int outputTokens,
        String failureReason,
        List<ExecutionStep> steps
) {

    public ExecutionResult {
        Objects.requireNonNull(output, "output must not be null");
        steps = steps != null ? List.copyOf(steps) : List.of();
    }

    /**
     * Total tokens consumed across all LLM calls in this pass ({@link #promptTokens} +
     * {@link #outputTokens}) — kept as a derived method, rather than a stored field, so
     * the two counts can never drift out of sync with their sum.
     */
    public int tokensUsed() {
        return promptTokens + outputTokens;
    }

    /**
     * Returns {@code true} if the strategy completed without a fatal error.
     *
     * @return {@code true} when {@code failureReason} is null or blank
     */
    public boolean isSuccess() {
        return failureReason == null || failureReason.isBlank();
    }

    /**
     * Returns the failure reason wrapped in an {@link Optional}.
     *
     * @return an optional failure reason string
     */
    public Optional<String> failureReasonOpt() {
        return !isSuccess() ? Optional.of(failureReason) : Optional.empty();
    }

    /** Creates a successful result with the prompt/output token split and an execution trace. */
    public static ExecutionResult success(String output, int iterations, int promptTokens, int outputTokens,
                                           List<ExecutionStep> steps) {
        return new ExecutionResult(true, output, iterations, promptTokens, outputTokens, null, steps);
    }

    /** Creates a successful result with the prompt/output token split, with no execution trace. */
    public static ExecutionResult success(String output, int iterations, int promptTokens, int outputTokens) {
        return new ExecutionResult(true, output, iterations, promptTokens, outputTokens, null, List.of());
    }

    /**
     * Creates a failed result with the prompt/output token split, a human-readable reason,
     * and a partial trace.
     */
    public static ExecutionResult failure(String reason, int iterations, int promptTokens, int outputTokens,
                                           List<ExecutionStep> steps) {
        return new ExecutionResult(false, "", iterations, promptTokens, outputTokens,
                Objects.requireNonNull(reason, "reason must not be null"), steps);
    }

    /** Creates a failed result with the prompt/output token split and a human-readable reason. */
    public static ExecutionResult failure(String reason, int iterations, int promptTokens, int outputTokens) {
        return new ExecutionResult(false, "", iterations, promptTokens, outputTokens,
                Objects.requireNonNull(reason, "reason must not be null"), List.of());
    }

    /**
     * Creates a failed result that still carries a partial output — e.g. a pipeline that
     * hit its step-count cap but has a real answer from the last step that ran
     * successfully before the cap stopped it. Distinct from {@link #failure(String, int,
     * int, int, List)} (which hardcodes an empty output): for most strategies a failure
     * has nothing safe to show as an answer, so that stays the default; a caller that
     * does have a genuine partial output uses this overload explicitly instead.
     */
    public static ExecutionResult failure(String reason, String partialOutput, int iterations,
                                           int promptTokens, int outputTokens, List<ExecutionStep> steps) {
        return new ExecutionResult(false, partialOutput != null ? partialOutput : "", iterations,
                promptTokens, outputTokens, Objects.requireNonNull(reason, "reason must not be null"), steps);
    }

    /**
     * Creates a successful, goal-achieved result from a single combined token count.
     *
     * @deprecated use {@link #success(String, int, int, int)} when the strategy tracks
     *             prompt/output tokens separately. Kept for strategies that only have a
     *             combined total: the full count is attributed to {@link #outputTokens},
     *             {@link #promptTokens} is {@code 0} — {@link #tokensUsed()} is unaffected
     *             either way.
     */
    @Deprecated(forRemoval = false)
    public static ExecutionResult success(String output, int iterations, int tokens) {
        return new ExecutionResult(true, output, iterations, 0, tokens, null, List.of());
    }

    /**
     * Creates a successful result with an execution trace, from a single combined token count.
     *
     * @deprecated see {@link #success(String, int, int)}.
     */
    @Deprecated(forRemoval = false)
    public static ExecutionResult success(String output, int iterations, int tokens, List<ExecutionStep> steps) {
        return new ExecutionResult(true, output, iterations, 0, tokens, null, steps);
    }

    /** Creates an intermediate result — goal not yet achieved, iteration continues. */
    public static ExecutionResult intermediate(String output, int iterations, int tokens) {
        return new ExecutionResult(false, output, iterations, 0, tokens, null, List.of());
    }

    /**
     * Creates a failed result with a human-readable reason, from a single combined token count.
     *
     * @deprecated see {@link #success(String, int, int)}.
     */
    @Deprecated(forRemoval = false)
    public static ExecutionResult failure(String reason, int iterations, int tokens) {
        return new ExecutionResult(false, "", iterations, 0, tokens,
                Objects.requireNonNull(reason, "reason must not be null"), List.of());
    }

    /**
     * Creates a failed result with a human-readable reason and partial trace, from a single
     * combined token count.
     *
     * @deprecated see {@link #success(String, int, int)}.
     */
    @Deprecated(forRemoval = false)
    public static ExecutionResult failure(String reason, int iterations, int tokens, List<ExecutionStep> steps) {
        return new ExecutionResult(false, "", iterations, 0, tokens,
                Objects.requireNonNull(reason, "reason must not be null"), steps);
    }
}
