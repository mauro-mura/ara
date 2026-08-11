package io.ara.core.agent;

import io.ara.core.common.AgentId;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A handle to an agent execution that may complete synchronously or asynchronously.
 *
 * <p>Wraps a {@link CompletableFuture}{@code <AgentResponse>} and adds the two things
 * plain {@code CompletableFuture} does not give you for free:
 * <ul>
 *   <li>{@link #thenExecute} — short-circuiting agent-to-agent chaining: the next agent
 *       is skipped when the current response is not a success, and the executor set at
 *       creation time is inherited so callers do not repeat it at every chain step.</li>
 *   <li>{@link #allOf} — fan-out aggregation via {@link AgentChain.MergeStrategy} and
 *       {@link AgentChain.FailurePolicy}.</li>
 * </ul>
 *
 * <p>{@code AgentFuture} lives in {@code ara-core} and uses only JDK types.
 * The {@link AgentChain.MergeStrategy} implementations that depend on Jackson live in
 * {@code MergeStrategies} in {@code ara-runtime}.
 */
public final class AgentFuture {

    private static final AgentId SYSTEM_AGENT_ID = AgentId.of("system");
    private static final String  UNKNOWN_TASK_ID  = "unknown";

    private final CompletableFuture<AgentResponse> delegate;
    /** Executor inherited by thenExecute — null for already-completed futures. */
    private final Executor executor;

    private AgentFuture(CompletableFuture<AgentResponse> f, Executor executor) {
        this.delegate = Objects.requireNonNull(f);
        this.executor = executor;   // nullable — completed() has no executor
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Result already available — synchronous path, zero overhead. */
    public static AgentFuture completed(AgentResponse response) {
        return new AgentFuture(CompletableFuture.completedFuture(
                Objects.requireNonNull(response)), null);
    }

    /**
     * Asynchronous execution on the provided executor.
     * The executor is stored so that {@link #thenExecute} inherits it automatically.
     */
    public static AgentFuture async(Supplier<AgentResponse> task, Executor executor) {
        return new AgentFuture(
                CompletableFuture.supplyAsync(
                        Objects.requireNonNull(task), Objects.requireNonNull(executor)),
                executor);
    }

    // ── Consumption ──────────────────────────────────────────────────────────

    /**
     * Blocks until completion.
     *
     * @throws AgentExecutionException if the computation thread threw an infrastructure
     *         exception that prevented production of any response
     */
    public AgentResponse get() {
        try {
            return delegate.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AgentExecutionException(null,
                    "Agent execution failed with unrecoverable error: " + describe(cause),
                    cause);
        } catch (CancellationException e) {
            // join() throws this un-wrapped, so the CompletionException branch never sees
            // it: without its own branch a cancelled future escapes as a raw JDK exception
            // instead of the AgentExecutionException this method documents.
            throw new AgentExecutionException(null, "Agent execution was cancelled", e);
        }
    }

    /**
     * Renders {@code t} for a failure message. {@link Throwable#getMessage()} is null for
     * plenty of common exceptions (e.g. {@link NullPointerException} without a message),
     * which would otherwise surface to the caller as the literal text {@code "null"}.
     */
    private static String describe(Throwable t) {
        if (t == null) return "unknown error";
        String message = t.getMessage();
        return (message != null && !message.isBlank()) ? message : t.getClass().getSimpleName();
    }

    /** Returns the underlying {@link CompletableFuture} for non-blocking composition. */
    public CompletableFuture<AgentResponse> async() { return delegate; }

    /** True if the result is already available without blocking. */
    public boolean isDone() { return delegate.isDone(); }

    // ── Composition ──────────────────────────────────────────────────────────

    /**
     * Chains a second agent. The executor is inherited from the current future —
     * no need to repeat it at every chain step.
     *
     * <p>If the current result is a failure, the chain short-circuits:
     * the next agent is not invoked.
     *
     * <pre>{@code
     * AgentFuture chain = AraAgents.executeAsync(agentA, task, runtime.executor())
     *     .thenExecute(agentB, r -> AgentTask.of(r.content()))
     *     .thenExecute(agentC, r -> AgentTask.of(r.content()));
     * }</pre>
     */
    public AgentFuture thenExecute(AraAgent next, Function<AgentResponse, AgentTask> taskBuilder) {
        Objects.requireNonNull(next, "next agent must not be null");
        Objects.requireNonNull(taskBuilder, "taskBuilder must not be null");
        Objects.requireNonNull(executor,
                "thenExecute requires an executor — use async() factory, not completed()");
        return new AgentFuture(delegate.thenCompose(response -> {
            if (!response.isSuccess()) return CompletableFuture.completedFuture(response);
            return AraAgents.executeAsync(next, taskBuilder.apply(response), executor).async();
        }), executor);
    }

    /** Convenience: uses the content as input to the next agent. */
    public AgentFuture thenExecute(AraAgent next) {
        return thenExecute(next, r -> AgentTask.of(r.content()));
    }

    // ── Fan-out ───────────────────────────────────────────────────────────────

    /**
     * Waits for all futures to complete and applies the merge strategy.
     * Exception-safe: any exception in the aggregation produces {@link AgentResponse#failure}.
     * The executor is inferred from the first non-null executor among the input futures
     * so that {@link #thenExecute} works on the returned future.
     *
     * @param futures       futures to complete in parallel — must not be empty
     * @param merge         aggregation strategy for the outputs
     * @param failurePolicy behaviour when one or more agents fail
     */
    public static AgentFuture allOf(List<AgentFuture> futures,
                                    AgentChain.MergeStrategy merge,
                                    AgentChain.FailurePolicy failurePolicy) {
        Objects.requireNonNull(futures, "futures must not be null");
        Objects.requireNonNull(merge, "merge must not be null");
        Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        if (futures.isEmpty()) {
            return AgentFuture.completed(AgentResponse.failure(UNKNOWN_TASK_ID, SYSTEM_AGENT_ID,
                    "allOf called with empty futures list", Duration.ZERO));
        }
        Executor inferredExecutor = futures.stream()
                .map(f -> f.executor)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        CompletableFuture<?>[] cfs = futures.stream()
                .map(f -> f.delegate).toArray(CompletableFuture[]::new);

        return new AgentFuture(CompletableFuture.allOf(cfs).handle((__, ex) -> {
            if (ex != null) {
                // `ex.getCause()` is null for a CompletionException built without one —
                // unwrapping blindly then calling getMessage() on it would turn an
                // aggregation failure into a NullPointerException inside handle().
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                        ? ex.getCause() : ex;
                return AgentResponse.failure(UNKNOWN_TASK_ID, SYSTEM_AGENT_ID,
                        "allOf aggregation error: " + describe(cause), Duration.ZERO);
            }
            List<AgentResponse> responses = futures.stream()
                    .map(f -> f.delegate.join()).toList();
            try {
                return failurePolicy.apply(responses, merge);
            } catch (RuntimeException e) {
                return AgentResponse.failure(UNKNOWN_TASK_ID, SYSTEM_AGENT_ID,
                        "MergeStrategy threw: " + describe(e), Duration.ZERO);
            }
        }), inferredExecutor);
    }

    /** Convenience with default policy {@link AgentChain.FailurePolicy#FAIL_FAST}. */
    public static AgentFuture allOf(List<AgentFuture> futures, AgentChain.MergeStrategy merge) {
        return allOf(futures, merge, AgentChain.FailurePolicy.FAIL_FAST);
    }
}
