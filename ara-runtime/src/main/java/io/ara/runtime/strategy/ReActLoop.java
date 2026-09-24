package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared skeleton of a ReAct-shaped reasoning loop, reused by {@link ReactStrategy},
 * {@link ReflActStrategy} and {@link ReSpActStrategy}.
 *
 * <p>Every strategy in the family repeats the same per-iteration invariants: cooperative
 * cancellation, the single task deadline, the cost/run budget checks, token accounting,
 * the one-shot synthesis nudge, and an LLM call bounded by that deadline. Keeping that
 * skeleton in each strategy meant a fix to any one of them (the interrupt restore, the
 * deadline re-check after the call, the budget charge) had to be copied correctly three
 * times — the same class of bug {@link ReactExecutionSupport} was extracted to avoid for
 * the dispatch/decision pieces. This class owns the skeleton; a subclass supplies only
 * what genuinely varies:
 *
 * <ul>
 *   <li>{@link #systemSuffix()} — the format instructions appended to the system prompt;</li>
 *   <li>{@link #decide} — the post-LLM decision and its side effects (tool dispatch via
 *       {@link #dispatch}, reflection, speak), returning a result to stop or {@code null}
 *       to loop again;</li>
 *   <li>{@link #onMaxIterations()} — the strategy-specific give-up message;</li>
 *   <li>the two optional behaviours {@link #injectSynthesis()} and {@link #logIterations()},
 *       which {@link ReSpActStrategy} deliberately disables.</li>
 * </ul>
 *
 * <p>State that the hooks need to read or mutate (iteration/token counters, the step
 * trace, the resolved tools) lives on this class as fields rather than being threaded
 * through each hook's parameter list.
 */
abstract class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    final AgentTask     task;
    final LlmClient     llm;
    final MemoryManager memory;
    final ToolRegistry  tools;
    final AgentConfig   config;
    final List<AraTool> resolvedTools;
    final boolean       nativeTools;
    final boolean       logIo;
    final int           logIoMaxChars;
    final Instant       deadline;
    final LlmCallContext ctx;
    final LlmCallContext stepCtx;
    final LlmCallContext forcedCtx;

    private final String toolCatalog;
    // The message list grows incrementally across iterations — see MessageBuffer.
    private final ReactExecutionSupport.MessageBuffer messageBuffer = new ReactExecutionSupport.MessageBuffer();

    final List<ExecutionStep> steps = new ArrayList<>();
    int     iterations;
    int     totalPromptTokens;
    int     totalOutputTokens;
    boolean synthesisModeActive;

    ReActLoop(AgentTask task, LlmClient llm, MemoryManager memory, ToolRegistry tools, AgentConfig config) {
        this.task          = task;
        this.llm           = llm;
        this.memory        = memory;
        this.tools         = tools;
        this.config        = config;
        this.logIo         = config.logLlmIo();
        this.logIoMaxChars = config.logLlmIoMaxChars();
        this.nativeTools   = llm.supportsNativeTools();
        this.deadline      = Instant.now().plus(config.executionTimeout());
        this.ctx           = LlmCallContext.of(config, task);

        // Resolve tools once — the list is stable for the lifetime of this execution.
        this.resolvedTools = tools.resolveEnabled(config.enabledTools());
        // stepCtx only ever differs by which tools are exposed: the full set on normal
        // iterations, an empty set on forced-final ones. All other fields are fixed for
        // the task, so precompute the two variants once instead of copying every field
        // per loop turn.
        this.stepCtx       = ctx.withResolvedTools(resolvedTools);
        this.forcedCtx     = ctx.withResolvedTools(List.of());
        // Same hoisting: the text catalog only varies by which tools are exposed, so
        // precompute the string once instead of re-serialising every schema per iteration.
        this.toolCatalog   = ReactExecutionSupport.toolCatalog(resolvedTools, nativeTools);
    }

    /** Runs the loop to completion, success or failure. */
    final ExecutionResult run() {
        logStart();
        while (iterations < config.maxIterations()) {
            // Cooperative cancellation: AgentInstance.terminate(session) interrupts this thread.
            if (Thread.currentThread().isInterrupted()) {
                log.debug("Task [{}] cancelled at iteration {} (thread interrupted)", task.taskId(), iterations);
                return cancelled();
            }
            if (Instant.now().isAfter(deadline)) {
                throw new ExecutionTimeoutException(config.executionTimeout());
            }

            ExecutionResult budgetExceeded = ReactExecutionSupport.checkBudget(
                    config, task.taskId(), totalPromptTokens, totalOutputTokens, iterations, steps);
            if (budgetExceeded != null) {
                return budgetExceeded;
            }

            iterations++;
            if (injectSynthesis()) {
                synthesisModeActive = ReactExecutionSupport.maybeInjectSynthesis(
                        memory, config, task, iterations, synthesisModeActive, resolvedTools, nativeTools);
            }

            // Hard stop: on the final iteration(s) withhold tools so the model cannot
            // emit a structured tool call and MUST produce plain text, which the forced
            // decision catches. This guarantees termination even for models that never
            // self-terminate.
            boolean forceFinal = iterations >= config.maxIterations() - 1;
            List<LlmMessage> messages = messageBuffer.build(memory, forceFinal ? "" : toolCatalog, systemSuffix());
            LlmCallContext iterationCtx = forceFinal ? forcedCtx : stepCtx;

            LlmCompletion completion;
            try {
                completion = ReactExecutionSupport.callLlm(llm, messages, iterationCtx, task, deadline, config);
            } catch (ExecutionTimeoutException te) {
                throw te;   // preserve timeout semantics for AgentInstance
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();   // restore the flag for our caller
                log.debug("Task [{}] cancelled during the LLM call at iteration {}", task.taskId(), iterations);
                return cancelled();
            } catch (Exception e) {
                log.warn("LLM call failed on iteration {} for task [{}]: {}",
                        iterations, task.taskId(), e.getMessage());
                log.debug("LLM exception detail", e);
                return ExecutionResult.failure(ReactExecutionSupport.describeLlmFailure(e),
                        iterations, totalPromptTokens, totalOutputTokens, steps);
            }

            if (Instant.now().isAfter(deadline)) {
                throw new ExecutionTimeoutException(config.executionTimeout());
            }

            totalPromptTokens += completion.promptTokens();
            totalOutputTokens += completion.outputTokens();
            String output = completion.text();

            ExecutionResult runBudgetExceeded = ReactExecutionSupport.chargeRunBudget(
                    config, task, completion.promptTokens(), completion.outputTokens(),
                    iterations, totalPromptTokens, totalOutputTokens, steps);
            if (runBudgetExceeded != null) {
                return runBudgetExceeded;
            }

            ReactExecutionSupport.recordAssistantOutput(memory, steps, completion, output, iterations, task.taskId());
            if (logIterations()) {
                ReactExecutionSupport.logIterationResult(completion, iterations, config.maxIterations(), task.taskId());
            }

            ExecutionResult decision = decide(output, completion, forceFinal, iterations);
            if (decision != null) {
                return decision;
            }
        }
        return onMaxIterations();
    }

    /**
     * Dispatches the calls of a {@link ReactExecutionSupport.StepDecision.DispatchTools}
     * decision on the shared {@link ReactExecutionSupport#dispatchSingle} /
     * {@link ReactExecutionSupport#dispatchParallel} path.
     *
     * @return {@code true} if any dispatched call failed — the signal
     *         {@link ReflActStrategy}'s reflect-on-tool-failure trigger reacts to
     */
    final boolean dispatch(List<ToolCallParser.ToolCallRequest> calls, LlmCompletion completion, int iteration) {
        ReActSupport.DispatchContext dispatchCtx = new ReActSupport.DispatchContext(
                tools, memory, steps, task, iteration, deadline, logIo, logIoMaxChars);
        if (calls.size() == 1) {
            // Single tool call — same deadline-bounded dispatch, plus the legacy
            // top-level toolCallId fallback batches never need.
            return ReactExecutionSupport.dispatchSingle(calls.get(0), completion.toolCallId(), dispatchCtx);
        }
        // Multiple tool calls — dispatch in parallel via virtual threads.
        log.debug("Parallel dispatch: {} tool calls for task [{}]", calls.size(), task.taskId());
        return ReactExecutionSupport.dispatchParallel(calls, dispatchCtx);
    }

    /** The strategy-neutral "Cancelled" failure, carrying the counters accumulated so far. */
    final ExecutionResult cancelled() {
        return ExecutionResult.failure("Cancelled", iterations, totalPromptTokens, totalOutputTokens, steps);
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    /** The format-instruction suffix appended to the system prompt on normal iterations. */
    abstract String systemSuffix();

    /**
     * Handles the current completion. Returns a non-null result to end the run, or
     * {@code null} to continue looping.
     *
     * @param forceFinal {@code true} on the last iteration(s), when tools were withheld
     */
    abstract ExecutionResult decide(String output, LlmCompletion completion, boolean forceFinal, int iteration);

    /** The strategy-specific failure produced when the loop exhausts {@code maxIterations}. */
    abstract ExecutionResult onMaxIterations();

    /** Logs the strategy's start banner. Overridden where the banner carries extra state. */
    void logStart() {}

    /** Whether the one-shot synthesis nudge applies — {@link ReSpActStrategy} opts out. */
    boolean injectSynthesis() { return true; }

    /** Whether to log each iteration's outcome — {@link ReSpActStrategy} opts out. */
    boolean logIterations() { return true; }
}
