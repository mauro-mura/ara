package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P0 row 1, §4 U1/U2 — regression test.
 *
 * <p>Before U1/U2, {@code PlanExecuteStrategy.dispatchTool} ran the tool call inline on the
 * reasoning thread with no deadline and no watchdog at all — the only tool-dispatch path in
 * the codebase without one ({@code ReactExecutionSupport.dispatchBounded}, exercised by
 * {@code ReactStrategy}/{@code ReSpActStrategy}/{@code ReflActStrategy}, already had a bound).
 * A hung tool (a stuck MCP server, a shell command still streaming) blocked the whole step,
 * and by extension the whole task, forever, ignoring {@code executionTimeout} entirely: the
 * step loop's own {@code checkTimeout} boundary check is only reached between rounds, never
 * while a round's own tool call is in flight. Mirrors {@code
 * ReactStrategyDeadlineAndRetryTest#executionTimeout_boundsASingleToolDispatchThatNeverReturns}.
 */
class PlanExecuteStrategyDeadlineTest {

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

    /** A tool that blocks forever — the plan_execute step-dispatch path used to have no bound at all. */
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

    /** First call produces a one-step plan; every call after that requests the hanging tool. */
    private static final class PlanThenHangToolLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return new LlmCompletion("1. Call the hang tool", 5, 5, "stop", null);
            }
            return new LlmCompletion("{\"tool_id\":\"hang\",\"arguments\":{}}", 5, 5, "tool_calls", null);
        }

        @Override public String providerId() { return "plan-then-hang-stub"; }
    }

    @Test
    void executionTimeout_boundsAStepsToolDispatchThatNeverReturns() throws InterruptedException {
        HangingTool tool = new HangingTool();
        PlanThenHangToolLlmClient llm = new PlanThenHangToolLlmClient();
        // See ReactStrategyDeadlineAndRetryTest for why this uses a generous deadline
        // instead of a tight one: the point is that a 30s hang gets cut off well short of
        // 30s, not that it happens at an exact millisecond.
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(2))
                .maxIterations(10)
                .enabledTools(List.of("hang"))
                .build();

        long start = System.nanoTime();
        assertThrows(ExecutionTimeoutException.class, () -> new PlanExecuteStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory("hello"), namedRegistry(tool), config));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(tool.interruptedSignal.await(5, TimeUnit.SECONDS),
                "deadline did not interrupt a hung plan_execute step tool dispatch");
        assertTrue(elapsedMs < 10_000,
                "a 30s hang should have been cut off in ~2s, took " + elapsedMs + "ms");
    }

    @Test
    void aNormalToolCall_stillCompletesSuccessfully_afterTheDeadlineWasAddedToDispatch() {
        LlmClient llm = new LlmClient() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                int n = calls.incrementAndGet();
                if (n == 1) return new LlmCompletion("1. Call the echo tool", 5, 5, "stop", null);
                if (n == 2) return new LlmCompletion(
                        "{\"tool_id\":\"echo\",\"arguments\":{}}", 5, 5, "tool_calls", null);
                if (n == 3) return new LlmCompletion("STEP_DONE: ok", 5, 5, "stop", null);
                return new LlmCompletion("Final answer: ok", 5, 5, "stop", null);
            }

            @Override public String providerId() { return "plan-then-echo-stub"; }
        };
        AraTool echo = new AraTool() {
            @Override public String toolId() { return "echo"; }
            @Override public String description() { return "echoes"; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
        };
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(10)
                .enabledTools(List.of("echo"))
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory("hello"), namedRegistry(echo), config);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
    }
}
