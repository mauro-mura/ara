package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the two nucleo-level fixes:
 * <ol>
 *   <li>{@code executionTimeout} now bounds a single blocking {@code llm.complete()} call
 *       and a single (non-parallel) tool dispatch — previously only checked at iteration
 *       boundaries, so a call/tool that never returned hung the whole task.</li>
 *   <li>{@code ReactExecutionSupport.completeWithRetry} retries an {@link LlmException}
 *       when {@link LlmException#isRetryable()} is {@code true}, and fails immediately
 *       otherwise — previously the strategy caught every exception generically and never
 *       consulted {@code isRetryable()} at all.</li>
 * </ol>
 */
class ReactStrategyDeadlineAndRetryTest {

    private static ToolRegistry namedRegistry(AraTool... tools) {
        var map = new HashMap<String, AraTool>();
        for (var t : tools) map.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) {
                return ids.isEmpty() ? List.copyOf(map.values())
                        : ids.stream().map(map::get).filter(Objects::nonNull).toList();
            }
            @Override public Optional<AraTool> findById(String id) { return Optional.ofNullable(map.get(id)); }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                var t = map.get(toolId);
                return t != null ? t.execute(argumentJson) : ToolResult.failure(toolId, "unknown tool");
            }
        };
    }

    private static MemoryManager seededMemory(String userInput) {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", userInput);
        return memory;
    }

    // ── Deadline: blocking LLM call ─────────────────────────────────────────────

    /** Blocks forever on {@code complete()} — never returns, never throws on its own. */
    private static final class HangingLlmClient implements LlmClient {
        final CountDownLatch interruptedSignal = new CountDownLatch(1);

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            try {
                // Sleep far longer than any test timeout — the watchdog must interrupt
                // this, not this method returning on its own, for the test to be a real
                // regression check.
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("unreachable", 0, 0, "stop", null);
        }

        @Override public String providerId() { return "hanging-stub"; }
    }

    @Test
    void executionTimeout_boundsABlockingLlmCallThatNeverReturns() throws InterruptedException {
        HangingLlmClient llm = new HangingLlmClient();
        // A generous deadline, not a tight one: the point of this test is that a 30s hang
        // gets cut off well short of 30s, not that it happens at an exact millisecond — a
        // deadline of a few hundred ms is indistinguishable from JVM/class-loading jitter
        // on the first test in a fresh fork, which would make this test flaky for a reason
        // that has nothing to do with the fix it verifies.
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(2))
                .maxIterations(10)
                .build();

        long start = System.nanoTime();
        // ExecutionTimeoutException propagates out of execute() by design — ReactStrategy
        // rethrows it so AgentInstance.executeTask() can apply its own timeout handling
        // (interceptor onTimeout hook, session reset). A strategy test calling execute()
        // directly, with no AgentInstance in front of it, sees that same exception.
        assertThrows(ExecutionTimeoutException.class, () -> new ReactStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory("hello"), ToolRegistry.empty(), config));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // The watchdog must have actually interrupted the blocked thread — proves the
        // deadline was enforced, not that the call happened to finish some other way.
        assertTrue(llm.interruptedSignal.await(5, TimeUnit.SECONDS),
                "deadline watchdog never interrupted the blocked LLM call");
        assertTrue(elapsedMs < 10_000,
                "a 30s hang should have been cut off in ~2s, took " + elapsedMs + "ms");
    }

    // ── Deadline: single (non-parallel) tool dispatch ───────────────────────────

    /** A tool that blocks forever — the single-call dispatch path used to have no bound at all. */
    private static final class HangingTool implements AraTool {
        final CountDownLatch interruptedSignal = new CountDownLatch(1);

        @Override public String toolId() { return "hang"; }
        @Override public String description() { return "hangs"; }
        @Override public String argumentSchema() { return "{}"; }

        @Override
        public ToolResult execute(String argumentJson) {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
            }
            return ToolResult.success(toolId(), "unreachable");
        }
    }

    @Test
    void executionTimeout_boundsASingleToolDispatchThatNeverReturns() throws InterruptedException {
        HangingTool tool = new HangingTool();
        LlmClient llm = new LlmClient() {
            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion(
                        "{\"tool_id\":\"hang\",\"arguments\":{}}", 5, 5, "tool_calls", null);
            }
            @Override public String providerId() { return "tool-caller-stub"; }
        };
        // See executionTimeout_boundsABlockingLlmCallThatNeverReturns for why this uses a
        // generous deadline instead of a tight one.
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(2))
                .maxIterations(10)
                .enabledTools(List.of("hang"))
                .build();

        long start = System.nanoTime();
        // The bounded dispatch gives up waiting once the deadline passes (interrupting the
        // hung worker) and the loop discovers the expired deadline at its next boundary
        // check, surfacing the same ExecutionTimeoutException as a hung LLM call.
        assertThrows(ExecutionTimeoutException.class, () -> new ReactStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory("hello"), namedRegistry(tool), config));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(tool.interruptedSignal.await(5, TimeUnit.SECONDS),
                "deadline did not interrupt a hung single-call tool dispatch");
        assertTrue(elapsedMs < 10_000,
                "a 30s hang should have been cut off in ~2s, took " + elapsedMs + "ms");
    }

    // ── Retry: transient LlmException is retried ────────────────────────────────

    /** Fails with a retryable {@link LlmException} a fixed number of times, then succeeds. */
    private static final class FlakyThenOkLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failuresBeforeSuccess;

        FlakyThenOkLlmClient(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        int callCount() { return calls.get(); }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            int n = calls.incrementAndGet();
            if (n <= failuresBeforeSuccess) {
                throw LlmException.rateLimit("test-provider", "simulated 429");
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 5, 5, "stop", null);
        }

        @Override public String providerId() { return "flaky-stub"; }
    }

    @Test
    void retryableLlmException_isRetriedAndEventuallySucceeds() {
        FlakyThenOkLlmClient llm = new FlakyThenOkLlmClient(2);
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(10)
                .build();

        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory("hello"), ToolRegistry.empty(), config);

        assertTrue(result.isSuccess(), "a transient rate limit must not fail the whole task");
        assertEquals("done", result.output());
        // 2 failed attempts + 1 successful attempt, all within a single reasoning iteration —
        // the retry loop must not have consumed extra maxIterations budget.
        assertEquals(3, llm.callCount());
        assertEquals(1, result.iterationsDone());
    }

    // ── Retry: non-retryable LlmException fails immediately, without retrying ──

    private static final class AlwaysAuthFailureLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        int callCount() { return calls.get(); }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.incrementAndGet();
            throw LlmException.authenticationError("test-provider", "invalid api key");
        }

        @Override public String providerId() { return "auth-fail-stub"; }
    }

    @Test
    void nonRetryableLlmException_failsImmediatelyWithoutRetrying() {
        AlwaysAuthFailureLlmClient llm = new AlwaysAuthFailureLlmClient();
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(10)
                .build();

        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory("hello"), ToolRegistry.empty(), config);

        assertFalse(result.isSuccess());
        assertEquals(1, llm.callCount(), "a non-retryable failure must not be retried");
        assertTrue(result.failureReasonOpt().orElse("").contains("AUTHENTICATION"),
                "failure reason should carry the typed LlmException detail: " + result.failureReasonOpt());
    }

    // ── Retry: backoff never borrows time the task does not have ───────────────

    @Test
    void retryBackoff_neverOverrunsTheExecutionDeadline() {
        // Always retryable, never succeeds — forces the retry loop to keep trying until
        // either it gives up on attempts or the deadline stops it first.
        LlmClient llm = new LlmClient() {
            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw LlmException.networkError("test-provider", "connection reset", null);
            }
            @Override public String providerId() { return "always-network-error-stub"; }
        };
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofMillis(400))
                .maxIterations(10)
                .build();

        long start = System.nanoTime();
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory("hello"), ToolRegistry.empty(), config);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertFalse(result.isSuccess());
        // Generous upper bound: the retry loop must respect the ~400ms deadline, not run
        // through its full backoff schedule (500ms + 1000ms + 2000ms = 3.5s+).
        assertTrue(elapsedMs < 2000,
                "retry loop overran the execution deadline: took " + elapsedMs + "ms");
    }
}
