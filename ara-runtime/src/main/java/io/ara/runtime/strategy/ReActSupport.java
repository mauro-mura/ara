package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;

import java.time.Instant;
import java.util.List;

/**
 * The public surface of ARA's shared ReAct-loop mechanics, for a consumer implementing
 * its own {@link io.ara.core.agent.ExecutionStrategy} outside this package.
 *
 * <p>ARA's built-in ReAct-shaped strategies ({@code react}, {@code respact}, {@code
 * reflact}) reuse a package-private internal class for everything they do *identically*:
 * materialising working memory into the LLM message list, calling the model with a
 * deadline and bounded retries, dispatching tool calls (sequentially or in parallel) with
 * interrupt propagation, and charging the run's cost/run budgets. A strategy written in
 * another package previously had to re-implement all of it — exactly the duplication the
 * internal class was extracted to avoid, with the same bugs (the streaming-subscription
 * cancel, the parallel-dispatch interrupt) re-appearing in each copy.
 *
 * <p>This class exposes those mechanics without opening the whole internal type: it
 * re-publishes the two value types a caller needs ({@link MessageBuffer}, {@link
 * DispatchContext}) and delegate methods for the operations, while the *protocol* half
 * — the {@code StepDecision}/{@code ForcedFinalDecision} logic and the iteration skeleton
 * — stays internal. That split is deliberate: the protocol is the part that varies per
 * strategy (ReSpAct's three-branch decision, PlanExecute's step isolation), so a consumer
 * brings its own and reuses only the mechanics, which are the same for everyone.
 *
 * <p>Stateless and safe to share: every method is static and reads no shared mutable
 * state beyond the process-wide deadline watchdog the LLM call uses.
 */
public final class ReActSupport {

    private ReActSupport() {}

    // ── Message building ──────────────────────────────────────────────────────

    /**
     * Incremental materialiser of the LLM message list from working memory, reused
     * across the iterations of one execution. Wraps the shared internal implementation;
     * see that class for the identity-based prefix-reuse contract.
     */
    public static final class MessageBuffer {

        private final ReactExecutionSupport.MessageBuffer delegate = new ReactExecutionSupport.MessageBuffer();

        /**
         * Returns the message list for the current working memory, reusing the cached
         * prefix when safe and appending only the newly added entries.
         *
         * @param toolCatalog the text catalog for this iteration — empty on forced-final
         *                    iterations, which also silences {@code suffix}
         * @param suffix      the format-instruction suffix appended after the catalog on
         *                    the first system message; ignored when {@code toolCatalog}
         *                    is empty
         */
        public List<LlmMessage> build(MemoryManager memory, String toolCatalog, String suffix) {
            return delegate.build(memory, toolCatalog, suffix);
        }
    }

    /**
     * The text tool catalog for one execution pass, computed once and reused on every
     * iteration. Empty when tools are withheld entirely (native function-calling, or no
     * tools resolved).
     */
    public static String toolCatalog(List<AraTool> resolvedTools, boolean nativeTools) {
        return ReactExecutionSupport.toolCatalog(resolvedTools, nativeTools);
    }

    /**
     * Injects the one-shot "wrap up" synthesis nudge near the end of the loop, if it is
     * due this iteration. Returns whether the nudge is active after this call.
     */
    public static boolean maybeInjectSynthesis(
            MemoryManager memory, AgentConfig config, AgentTask task, int iterations, boolean wasActive,
            List<AraTool> resolvedTools, boolean nativeTools) {
        return ReactExecutionSupport.maybeInjectSynthesis(
                memory, config, task, iterations, wasActive, resolvedTools, nativeTools);
    }

    /** Builds the synthesis-prompt text for the given clause options. */
    public static String buildSynthesisPrompt(boolean includePersistClause, boolean includeSentinelClause) {
        return ReactExecutionSupport.buildSynthesisPrompt(includePersistClause, includeSentinelClause);
    }

    // ── LLM call: deadline enforcement, retry, streaming ──────────────────────

    /**
     * One reasoning step's LLM call: streaming when the agent asked for it and the caller
     * supplied a token sink, blocking otherwise; both bounded by {@code deadline}.
     *
     * @throws io.ara.core.agent.ExecutionTimeoutException if {@code deadline} passes first
     * @throws InterruptedException                        if the calling thread is cancelled
     */
    public static LlmCompletion callLlm(
            LlmClient llm, List<LlmMessage> messages, LlmCallContext stepCtx,
            AgentTask task, Instant deadline, AgentConfig config) throws InterruptedException {
        return ReactExecutionSupport.callLlm(llm, messages, stepCtx, task, deadline, config);
    }

    /**
     * Calls {@link LlmClient#complete} with bounded retries on transient provider failures,
     * bounded by {@code deadline}.
     */
    public static LlmCompletion completeWithRetry(
            LlmClient llm, List<LlmMessage> messages, LlmCallContext ctx,
            Instant deadline, AgentConfig config, String taskId) throws InterruptedException {
        return ReactExecutionSupport.completeWithRetry(llm, messages, ctx, deadline, config, taskId);
    }

    /**
     * Calls {@link LlmClient#stream} and blocks until the stream completes, forwarding
     * tokens to the task's callback, bounded by {@code deadline}.
     */
    public static LlmCompletion streamAndCollect(
            LlmClient llm, List<LlmMessage> messages, LlmCallContext ctx,
            AgentTask task, Instant deadline, AgentConfig config) throws InterruptedException {
        return ReactExecutionSupport.streamAndCollect(llm, messages, ctx, task, deadline, config);
    }

    /** A short description of an LLM failure for logs, including provider error-type detail. */
    public static String describeLlmFailure(Throwable e) {
        return ReactExecutionSupport.describeLlmFailure(e);
    }

    /** Truncates a string for INFO-level I/O logging; {@code maxChars <= 0} disables truncation. */
    public static String truncate(String s, int maxChars) {
        return ReactExecutionSupport.truncate(s, maxChars);
    }

    /** Rough token estimate from a character count (~4 chars/token). */
    public static int estimateTokensFromChars(int chars) {
        return ReactExecutionSupport.estimateTokensFromChars(chars);
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    /**
     * The parameters the shared dispatch helpers need — also the canonical record the
     * internal {@link ReactExecutionSupport} dispatch methods take, so there is a single
     * shape rather than a public mirror plus an internal copy converted at the boundary.
     *
     * <p>{@code steps} must be a <em>mutable</em> list: dispatch appends the tool-call and
     * observation steps it produces. Pass the same accumulator list the caller returns in
     * its {@code ExecutionResult}.
     */
    public record DispatchContext(
            ToolRegistry tools,
            MemoryManager memory,
            List<ExecutionStep> steps,
            AgentTask task,
            int iteration,
            Instant deadline,
            boolean logIo,
            int logIoMaxChars) {
    }

    /**
     * Dispatches a single tool call and appends the observation to memory.
     *
     * @return {@code true} if the tool call failed
     */
    public static boolean dispatchSingle(
            ToolCallParser.ToolCallRequest tcr, String legacyToolCallId, DispatchContext ctx) {
        return ReactExecutionSupport.dispatchSingle(tcr, legacyToolCallId, ctx);
    }

    /**
     * Dispatches several tool calls concurrently on virtual threads, bounded by the
     * context's deadline, and appends observations to memory in submission order.
     *
     * @return {@code true} if any dispatched call failed
     */
    public static boolean dispatchParallel(
            List<ToolCallParser.ToolCallRequest> calls, DispatchContext ctx) {
        return ReactExecutionSupport.dispatchParallel(calls, ctx);
    }

    // ── Budget ────────────────────────────────────────────────────────────────

    /**
     * Returns a failure result if the next LLM call would exceed the agent's configured
     * cost budget, else {@code null} to proceed.
     */
    public static ExecutionResult checkBudget(
            AgentConfig config, String taskId, int totalPromptTokens, int totalOutputTokens,
            int iterations, List<ExecutionStep> steps) {
        return ReactExecutionSupport.checkBudget(
                config, taskId, totalPromptTokens, totalOutputTokens, iterations, steps);
    }

    /**
     * Charges the task's run budget (when its {@link io.ara.core.agent.RunContext} carries
     * one) for one LLM call; returns a failure result naming the exceeded axis, or
     * {@code null} to continue.
     */
    public static ExecutionResult chargeRunBudget(
            AgentConfig config, AgentTask task, int promptTokens, int outputTokens,
            int iterations, int totalPromptTokens, int totalOutputTokens, List<ExecutionStep> steps) {
        return ReactExecutionSupport.chargeRunBudget(
                config, task, promptTokens, outputTokens, iterations,
                totalPromptTokens, totalOutputTokens, steps);
    }
}
