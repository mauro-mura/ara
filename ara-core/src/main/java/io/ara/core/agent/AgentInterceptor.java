package io.ara.core.agent;

import io.ara.core.llm.LlmCompletion;
import io.ara.core.tool.ToolResult;

import java.time.Duration;

/**
 * Cross-cutting concern applied before and after every step of the ReAct loop.
 *
 * <p>Interceptors are composed into an {@code AgentInterceptorChain} by the
 * {@code AgentFactory}. Each interceptor is invoked in order on the way in
 * ({@link #before}, {@link #beforeThink}, {@link #beforeToolCall}) and in reverse
 * order on the way out ({@link #after}, {@link #afterThink}, {@link #afterToolCall},
 * {@link #onError} and its typed siblings {@link #onBudgetExceeded}, {@link #onTimeout},
 * {@link #onCancelled}).
 *
 * <p>{@link #before}/{@link #after}/{@link #onError} bracket the coarse phases of a task
 * ({@code "Planning"}, {@code "Executing"}). The {@code *Think}/{@code *ToolCall} hooks
 * below are finer-grained: they fire once per LLM call / tool dispatch made by the
 * strategy while {@code "Executing"} is in progress, so an interceptor can observe
 * individual reasoning steps and tool invocations rather than only the task's start
 * and end. All of them default to a no-op, so existing interceptors that only
 * override {@link #before}/{@link #after}/{@link #onError} keep compiling and behaving
 * exactly as before.
 *
 * <p>Custom interceptors can be registered by passing them to {@code AgentFactory}.
 */
public interface AgentInterceptor {

    /**
     * Called immediately before a ReAct loop step executes.
     *
     * @param context a mutable view of the current execution context
     * @param stepName the name of the step about to execute (e.g. "Think", "Act")
     */
    void before(AgentExecutionContext context, String stepName);

    /**
     * Called immediately after a ReAct loop step completes successfully.
     *
     * @param context  the updated execution context after the step
     * @param stepName the name of the step that just completed
     * @param result   a string representation of the step's output (may be empty)
     */
    void after(AgentExecutionContext context, String stepName, String result);

    /**
     * Called when a ReAct loop step throws an unexpected exception.
     *
     * @param context   the execution context at the time of failure
     * @param stepName  the name of the step that failed
     * @param throwable the exception that was thrown
     */
    void onError(AgentExecutionContext context, String stepName, Throwable throwable);

    /**
     * Called immediately before the strategy asks the LLM to reason about the next
     * action (one call per ReAct iteration; more than once per iteration for
     * strategies that make several LLM calls, e.g. planning + replanning).
     *
     * @param context the execution context at the time of the call
     */
    default void beforeThink(AgentExecutionContext context) {
        // no-op by default
    }

    /**
     * Called immediately after an LLM call completes successfully.
     *
     * @param context    the execution context at the time of the call
     * @param completion the LLM's completion — text, token usage, finish reason, and
     *                   any requested tool calls
     */
    default void afterThink(AgentExecutionContext context, LlmCompletion completion) {
        // no-op by default
    }

    /**
     * Called immediately before a tool call requested by the LLM is dispatched.
     *
     * @param context      the execution context at the time of dispatch
     * @param toolId       the identifier of the tool about to be invoked
     * @param argumentJson the JSON-serialised arguments the LLM supplied
     */
    default void beforeToolCall(AgentExecutionContext context, String toolId, String argumentJson) {
        // no-op by default
    }

    /**
     * Called immediately after a tool call completes, successfully or not.
     *
     * @param context      the execution context at the time of completion
     * @param toolId       the identifier of the tool that was invoked
     * @param argumentJson the JSON-serialised arguments the LLM supplied
     * @param result       the tool's result, including success/failure and output
     */
    default void afterToolCall(AgentExecutionContext context, String toolId, String argumentJson, ToolResult result) {
        // no-op by default
    }

    /**
     * Called when a task fails because it exceeded its configured cost budget
     * ({@code AgentConfig.costBudget()}) — an expected, policy-driven stop rather
     * than an unexpected exception, so it is reported here instead of {@link #onError}.
     *
     * @param context  the execution context at the time of failure
     * @param stepName the name of the step that was in progress
     * @param reason   a human-readable description of the budget that was exceeded
     */
    default void onBudgetExceeded(AgentExecutionContext context, String stepName, String reason) {
        // no-op by default
    }

    /**
     * Called when a task fails because it exceeded its configured execution timeout
     * ({@code AgentConfig.executionTimeout()}) — reported here instead of {@link
     * #onError} since it is an expected, policy-driven stop.
     *
     * @param context  the execution context at the time of failure
     * @param stepName the name of the step that was in progress
     * @param timeout  the configured timeout duration that was exceeded
     */
    default void onTimeout(AgentExecutionContext context, String stepName, Duration timeout) {
        // no-op by default
    }

    /**
     * Called when a task is cancelled — either before execution started or mid-loop
     * via {@code AraAgent.terminate(SessionId)} — reported here instead of {@link
     * #onError} since it is a deliberate stop, not a failure.
     *
     * @param context  the execution context at the time of cancellation
     * @param stepName the name of the step that was in progress
     */
    default void onCancelled(AgentExecutionContext context, String stepName) {
        // no-op by default
    }

    /**
     * Called on the delegating agent's own interceptor chain immediately before a
     * {@code delegate_task} tool call is dispatched to a peer agent — the
     * delegation-specific counterpart of {@link #beforeToolCall}, with the recipient
     * and sub-task already parsed out of the tool's argument JSON so interceptors don't
     * need to decode it themselves to build a multi-agent trace.
     *
     * @param context           the execution context at the time of dispatch
     * @param recipientAgentId  the id of the peer agent the task is delegated to
     * @param delegatedTask     the sub-task text handed to the recipient
     */
    default void onDelegate(AgentExecutionContext context, String recipientAgentId, String delegatedTask) {
        // no-op by default
    }

    /**
     * Called on the delegating agent's own interceptor chain immediately after a
     * delegated sub-task returns (successfully or not) — the delegation-specific
     * counterpart of {@link #afterToolCall}.
     *
     * @param context          the execution context at the time of completion
     * @param recipientAgentId the id of the peer agent that was delegated to
     * @param result           the delegated call's result, as returned by the {@code
     *                         delegate_task} tool
     */
    default void onDelegateReturn(AgentExecutionContext context, String recipientAgentId, ToolResult result) {
        // no-op by default
    }
}
