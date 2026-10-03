package io.ara.runtime.agent;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.ContractViolation;

/**
 * Bounded classification of an {@link AgentResponse#failureReason()}.
 *
 * <p>Deliberately a small fixed set of labels, never the raw reason text (which can embed
 * an arbitrary exception message), so the {@code agent.failure_kind} telemetry attribute
 * stays safe to leave on an always-on span. An enum rather than bare strings so that
 * consumers switching on it are checked by the compiler instead of matching literals that
 * can silently drift out of sync with {@link #classify}.
 *
 * <p>Extracted from {@code AgentInstance} so the mapping can be unit-tested directly
 * rather than only observed through a span attribute.
 *
 * <p>Public since ADR-0074 D6: the observability dashboard's "top failure modes" panel
 * buckets {@code TraceSpan.failureKind} (the {@link #name()} of this enum) by value, and
 * that dashboard is a consumer in a separate, non-public module — it needs this type to
 * be public even though the dashboard itself is not part of this distribution.
 *
 * <p>This is the only failure taxonomy in this module, and it classifies <em>why execution
 * stopped</em> (timeout, budget exceeded, an unhandled exception, …) — never <em>whether a
 * completed answer was wrong</em>. A run can finish with {@code failureKind = null} (no
 * execution failure) and still have produced a bad answer; judging that is a separate,
 * orthogonal concern handled elsewhere and out of scope for this type.
 *
 * <p><strong>Known limitation.</strong> {@link #classify} matches on message text, so a
 * reworded failure reason in a strategy silently degrades to {@link #OTHER}. The structural
 * fix is a typed failure cause on {@code ExecutionResult}; until then the reason strings
 * this class matches are effectively a contract with the built-in strategies, and
 * {@code FailureKindTest} is what pins it down.
 */
public enum FailureKind {

    /** A task arrived while the session was already executing another one. */
    SESSION_BUSY,
    /** The agent or the session was cancelled before or during execution. */
    CANCELLED,
    /** The strategy exceeded the configured execution timeout. */
    TIMEOUT,
    /** The configured cost budget was exhausted. */
    BUDGET_EXCEEDED,
    /** The strategy ran out of reasoning iterations without producing a final answer. */
    MAX_ITERATIONS,
    /** An exception escaped the strategy. */
    UNEXPECTED_ERROR,
    /**
     * The agent's {@code AgentContract} refused the <em>request</em> — its input or its media — before the
     * model was called ({@link ContractViolation.Phase#callerFault()}). Never produced by {@link #classify}:
     * only {@link #of(AgentResponse)} can tell, because it reads the typed violation, not the reason text.
     */
    CONTRACT_REQUEST_VIOLATION,
    /**
     * The agent's {@code AgentContract} refused the <em>answer</em> — the output schema or an output
     * processor — after every repair attempt the contract allows. The agent did answer, and its own contract
     * said no: unlike the other kinds, this one names a failure that has a semantic cause. Never produced by
     * {@link #classify}; see {@link #of(AgentResponse)}.
     */
    CONTRACT_OUTPUT_VIOLATION,
    /** Anything not recognised — never a guess. */
    OTHER;

    /**
     * The kind of a failed response — from its typed {@link AgentResponse#violation()} when the agent's
     * contract refused the task, and otherwise from the reason text, as {@link #classify} does.
     *
     * <p>The typed route is the one thing {@link #classify}'s "known limitation" asks for, for the one
     * failure that already carries a typed cause: a contract refusal is recognised by what it <em>is</em>,
     * so rewording the message cannot make it slide into {@link #OTHER}.
     */
    public static FailureKind of(AgentResponse response) {
        if (response.violation() != null) {
            return response.violation().phase().callerFault() ? CONTRACT_REQUEST_VIOLATION : CONTRACT_OUTPUT_VIOLATION;
        }
        return classify(response.failureReason());
    }

    /**
     * Best-effort classification of a failure reason. Matched by literal prefix/substring
     * against the reason strings {@code AgentInstance} and the built-in strategies are known
     * to produce; anything unrecognised (including {@code null}) yields {@link #OTHER}.
     */
    public static FailureKind classify(String reason) {
        if (reason == null) return OTHER;
        if (reason.startsWith("Session busy")) return SESSION_BUSY;
        if (reason.equals("Cancelled") || reason.equals("Agent terminated before execution")) return CANCELLED;
        if (reason.startsWith("Execution exceeded timeout")) return TIMEOUT;
        if (reason.startsWith("Cost budget")) return BUDGET_EXCEEDED;
        if (reason.contains("Max iterations")) return MAX_ITERATIONS;
        if (reason.startsWith("Unexpected error:")) return UNEXPECTED_ERROR;
        return OTHER;
    }
}
