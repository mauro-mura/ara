package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.agent.StrategyConfig;
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
 * {@code docs/analysis/concurrency-hardening.md} §3 P0 rows 2/3, §4 U3/U5 — regression tests.
 *
 * <p>Before U3/U5, both {@code ReflActStrategy.reflect} and {@code
 * ReflexionStrategy.generateReflection} called {@code LlmClient.complete(...)} raw, with no
 * deadline at all — a hung reflection call (the client never returns, never throws) blocked
 * the task forever, ignoring {@code executionTimeout} entirely; {@code ReflexionStrategy} had
 * no deadline anywhere in its retry loop either (U5), so nothing would have bounded it even
 * indirectly. Mirrors {@code ReactStrategyDeadlineAndRetryTest}'s hanging-client pattern.
 */
class ReflectionDeadlineTest {

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

    private static AraTool failingTool() {
        return new AraTool() {
            @Override public String toolId()         { return "flaky"; }
            @Override public String description()    { return "always fails"; }
            @Override public String argumentSchema()  { return "{}"; }
            @Override public ToolResult execute(String argsJson) { return ToolResult.failure("flaky", "boom"); }
        };
    }

    private static MemoryManager seededMemory(String userInput) {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", userInput);
        return memory;
    }

    // ── U3: ReflActStrategy.reflect ─────────────────────────────────────────────

    /** First call requests the always-failing tool; every call after that hangs (the reflection call). */
    private static final class ToolCallThenHangLlmClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch interruptedSignal = new CountDownLatch(1);

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            if (calls.incrementAndGet() == 1) {
                return new LlmCompletion(
                        "{\"tool_id\":\"flaky\",\"arguments\":{}}", 5, 5, "tool_calls", null);
            }
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("unreachable", 0, 0, "stop", null);
        }

        @Override public String providerId() { return "tool-call-then-hang-stub"; }
    }

    @Test
    void reflActReflection_boundedByExecutionTimeout_whenTheReflectionCallHangs() throws InterruptedException {
        ToolCallThenHangLlmClient llm = new ToolCallThenHangLlmClient();
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(2))
                .maxIterations(10)
                .strategyConfig(StrategyConfig.ReflAct.defaults())
                .build();

        long start = System.nanoTime();
        assertThrows(ExecutionTimeoutException.class, () -> new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, seededMemory("do it"), namedRegistry(failingTool()), config));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(llm.interruptedSignal.await(5, TimeUnit.SECONDS),
                "deadline watchdog never interrupted the hung reflection call");
        assertTrue(elapsedMs < 10_000,
                "a 30s hang should have been cut off in ~2s, took " + elapsedMs + "ms");
    }

    // ── U5/U3: ReflexionStrategy's own retry-loop deadline + generateReflection ─

    private static ExecutionStrategy alwaysFailingDelegate() {
        return new ExecutionStrategy() {
            @Override public String strategyName() { return "always-fails"; }
            @Override public ExecutionResult execute(AgentTask t, LlmClient llm, MemoryManager mem,
                                                       ToolRegistry tools, AgentConfig cfg) {
                return ExecutionResult.failure("always fails", 1, 10);
            }
        };
    }

    /** Blocks forever on {@code complete()} — the reflection call in ReflexionStrategy's retry loop. */
    private static final class HangingLlmClient implements LlmClient {
        final CountDownLatch interruptedSignal = new CountDownLatch(1);

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("unreachable", 0, 0, "stop", null);
        }

        @Override public String providerId() { return "hanging-reflection-stub"; }
    }

    @Test
    void reflexionRetryLoop_boundedByExecutionTimeout_whenTheReflectionCallHangs() throws InterruptedException {
        HangingLlmClient llm = new HangingLlmClient();
        AgentConfig config = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(2))
                .strategyConfig(new StrategyConfig.Reflexion(2, null, null))
                .build();
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "sys");
        memory.appendToWorkingMemory("user", "do it");

        long start = System.nanoTime();
        assertThrows(ExecutionTimeoutException.class, () -> new ReflexionStrategy(alwaysFailingDelegate())
                .execute(AgentTask.of("do it"), llm, memory, ToolRegistry.empty(), config));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(llm.interruptedSignal.await(5, TimeUnit.SECONDS),
                "deadline watchdog never interrupted the hung reflection call");
        assertTrue(elapsedMs < 10_000,
                "a 30s hang should have been cut off in ~2s, took " + elapsedMs + "ms");
    }

    @Test
    void reflexionRetryLoop_boundedByExecutionTimeout_evenWithAWellBehavedButAlwaysFailingDelegate()
            throws InterruptedException {
        // U5: before this fix there was no deadline anywhere in ReflexionStrategy's own loop —
        // only maxReflections bounded it, which a large config value (or one left at a
        // permissive default) would not catch within any reasonable time. A delegate that
        // fails immediately, every time, combined with an instant (non-hanging) reflection
        // client, must still respect executionTimeout once enough attempts have run.
        AtomicInteger reflectionCalls = new AtomicInteger();
        LlmClient instantReflectionLlm = new LlmClient() {
            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                reflectionCalls.incrementAndGet();
                return new LlmCompletion("A reflection.", 5, 5, "stop", null);
            }
            @Override public String providerId() { return "instant-reflection-stub"; }
        };
        AgentConfig config = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofMillis(200))
                .strategyConfig(new StrategyConfig.Reflexion(1_000_000, null, null))   // effectively unbounded by count
                .build();
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "sys");
        memory.appendToWorkingMemory("user", "do it");

        long start = System.nanoTime();
        assertThrows(ExecutionTimeoutException.class, () -> new ReflexionStrategy(alwaysFailingDelegate())
                .execute(AgentTask.of("do it"), instantReflectionLlm, memory, ToolRegistry.empty(), config));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMs < 5_000,
                "a huge maxReflections must not let the loop run past executionTimeout, took " + elapsedMs + "ms");
    }
}
