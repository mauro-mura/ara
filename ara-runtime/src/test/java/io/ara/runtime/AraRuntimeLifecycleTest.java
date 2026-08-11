package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.config.AraRuntimeConfig;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle & builder hardening for {@link AraRuntime} (README §1 critiques):
 * <ul>
 *   <li>#7 — {@code build()} fails fast when {@code defaultLlmClient(id)} names a client
 *       that was never registered, instead of NPE-ing later inside the router.</li>
 *   <li>#2 — {@code extraStrategies(...)} accumulates across calls instead of the second
 *       call silently discarding the first.</li>
 *   <li>#1/#3 — {@code start()}/{@code stop()} are idempotent and a stopped runtime can
 *       be restarted (a fresh executor is provisioned).</li>
 * </ul>
 */
class AraRuntimeLifecycleTest {

    /** Minimal strategy that echoes a fixed marker, so we can prove it was registered/resolved. */
    private record MarkerStrategy(String name) implements ExecutionStrategy {
        @Override
        public ExecutionResult execute(AgentTask task, io.ara.core.llm.LlmClient llm,
                                       io.ara.core.memory.MemoryManager memory,
                                       io.ara.core.tool.ToolRegistry tools, AgentConfig config) {
            return ExecutionResult.success("ran:" + name, 1, 0);
        }
        @Override public String strategyName() { return name; }
    }

    private static AraRuntime.Builder baseBuilder() {
        return AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build());
    }

    // ── #7 — default LLM client validation ──────────────────────────────────

    @Test
    void build_rejectsDefaultLlmClientThatIsNotRegistered() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                AraRuntime.builder()
                        .llmClient("claude", ScriptedLlmClient.script().thenFinalAnswer("x").build())
                        .defaultLlmClient("gpt-4")   // never registered
                        .build());
        assertTrue(ex.getMessage().contains("gpt-4"), () -> "message should name the bad id: " + ex.getMessage());
    }

    @Test
    void build_acceptsDefaultThatIsRegistered() {
        assertDoesNotThrow(() ->
                AraRuntime.builder()
                        .llmClient("claude", ScriptedLlmClient.script().thenFinalAnswer("x").build())
                        .defaultLlmClient("claude")
                        .build());
    }

    // ── #2 — extraStrategies accumulate across calls ────────────────────────

    @Test
    void extraStrategies_accumulateAcrossMultipleCalls() {
        AraRuntime runtime = baseBuilder()
                .extraStrategies(new MarkerStrategy("alpha"))
                .extraStrategies(new MarkerStrategy("beta"))   // must NOT drop "alpha"
                .build();

        AraAgent a = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("a")).agentType("t")
                .primaryLlm(LlmProfile.of("default")).plannerStrategy("alpha").build());
        AraAgent b = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("b")).agentType("t")
                .primaryLlm(LlmProfile.of("default")).plannerStrategy("beta").build());

        AgentResponse ra = a.execute(AgentTask.of("hi"));
        AgentResponse rb = b.execute(AgentTask.of("hi"));

        assertTrue(ra.isSuccess(), "first-registered strategy 'alpha' must survive the second extraStrategies() call");
        assertEquals("ran:alpha", ra.content());
        assertTrue(rb.isSuccess());
        assertEquals("ran:beta", rb.content());

        runtime.stop();
    }

    // ── #1/#3 — start/stop idempotency and restart ──────────────────────────

    @Test
    void startStop_areIdempotent_andRuntimeCanRestart() {
        AraRuntime runtime = baseBuilder().build();

        assertNull(runtime.executor(), "executor is provisioned only on start()");
        runtime.start();
        runtime.start();   // idempotent — no second executor / no exception
        assertTrue(runtime.isRunning());
        assertNotNull(runtime.executor());

        runtime.stop();
        runtime.stop();    // idempotent
        assertFalse(runtime.isRunning());

        // a stopped runtime can be restarted with a fresh executor
        runtime.start();
        assertTrue(runtime.isRunning());
        assertNotNull(runtime.executor());
        runtime.stop();
    }

    // ── AutoCloseable and use-after-stop guards ──────────────────────────────

    private static AgentConfig minimalConfig(String id) {
        return AgentConfig.defaults()
                .agentId(AgentId.of(id)).agentType("t")
                .primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .build();
    }

    @Test
    void tryWithResources_stopsTheRuntime() {
        AraRuntime escaped;
        try (AraRuntime runtime = baseBuilder().build()) {
            runtime.start();
            assertTrue(runtime.isRunning());
            escaped = runtime;
        }
        assertFalse(escaped.isRunning(), "close() must delegate to stop()");
    }

    @Test
    void createAgent_afterStop_throwsInsteadOfSilentlyRestarting() {
        AraRuntime runtime = baseBuilder().build();
        runtime.start();
        runtime.stop();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.createAgent(minimalConfig("late")));
        assertTrue(ex.getMessage().contains("start()"),
                () -> "message should point at the explicit restart path: " + ex.getMessage());
        assertFalse(runtime.isRunning(), "the failed call must not have restarted the runtime");
    }

    @Test
    void submit_afterStop_throwsInsteadOfUsingDeadExecutor() {
        AraRuntime runtime = baseBuilder().build();
        AraAgent agent = runtime.createAgent(minimalConfig("s"));   // auto-starts (NEW → STARTED)
        runtime.stop();

        assertThrows(IllegalStateException.class,
                () -> runtime.submit(agent, AgentTask.of("hi")));
    }

    @Test
    void explicitRestart_reenablesAgentCreation() {
        AraRuntime runtime = baseBuilder().build();
        runtime.start();
        runtime.stop();

        runtime.start();   // documented restart path stays available
        assertDoesNotThrow(() -> runtime.createAgent(minimalConfig("again")));
        runtime.stop();
    }

    @Test
    void stopBeforeStart_isNoop_andFirstUseAutoStartStillWorks() {
        AraRuntime runtime = baseBuilder().build();
        runtime.stop();   // never started: must not poison the runtime

        assertDoesNotThrow(() -> runtime.createAgent(minimalConfig("first")));
        assertTrue(runtime.isRunning());
        runtime.stop();
    }

    // ── builder: unnamed llmClient() is the default, in any call order ──────

    @Test
    void unnamedLlmClient_becomesDefault_evenAfterNamedRegistration() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("claude", ScriptedLlmClient.script().thenFinalAnswer("from-claude").build())
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("from-default").build())
                .build();

        // primaryLlm names no registered client — the router falls back to the default
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("fb")).agentType("t")
                .primaryLlm(LlmProfile.of("no-such-provider")).plannerStrategy("react")
                .build());
        AgentResponse r = agent.execute(AgentTask.of("hi"));

        assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
        assertEquals("from-default", r.content(),
                "llmClient(client) is documented as registering THE default — a preceding "
                + "named registration must not silently keep the default role");
        runtime.stop();
    }

    // ── stop() honors AraRuntimeConfig.shutdownTimeoutSec ───────────────────

    /**
     * Strategy that blocks well past the configured drain timeout and shrugs off
     * interrupts. A cooperative task would already be stopped by {@code terminate()}
     * during {@code stop()}'s destroy phase, and the drain would never time out — the
     * non-cooperative task is exactly the scenario the drain timeout exists for.
     */
    private record SleepingStrategy(long sleepMs) implements ExecutionStrategy {
        @Override
        public ExecutionResult execute(AgentTask task, io.ara.core.llm.LlmClient llm,
                                       io.ara.core.memory.MemoryManager memory,
                                       io.ara.core.tool.ToolRegistry tools, AgentConfig config) {
            long deadline = System.nanoTime() + sleepMs * 1_000_000L;
            long remainingMs;
            while ((remainingMs = (deadline - System.nanoTime()) / 1_000_000L) > 0) {
                try {
                    Thread.sleep(remainingMs);
                } catch (InterruptedException e) {
                    // ignore and keep sleeping — simulates a task that won't cooperate
                }
            }
            Thread.currentThread().interrupt();   // restore the swallowed interrupt
            return ExecutionResult.success("slept", 1, 0);
        }
        @Override public String strategyName() { return "sleeper"; }
    }

    @Test
    void stop_honorsConfiguredShutdownTimeout() throws InterruptedException {
        AraRuntime runtime = baseBuilder()
                .runtimeConfig(AraRuntimeConfig.builder().shutdownTimeoutSec(1).build())
                .extraStrategies(new SleepingStrategy(8_000))
                .build();
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("slow")).agentType("t")
                .primaryLlm(LlmProfile.of("default")).plannerStrategy("sleeper")
                .build());

        runtime.submit(agent, AgentTask.of("hi"));
        Thread.sleep(200);   // let the task actually occupy the executor

        long t0 = System.nanoTime();
        runtime.stop();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // The drain must actually wait the configured 1s (lower bound proves the
        // timeout path ran at all), then shutdownNow() forces it — well short of the
        // 8s task. A regression to a hardcoded timeout, or to waiting for task
        // completion, blows past 6s.
        assertTrue(elapsedMs >= 900,
                () -> "stop() returned before the 1s drain even elapsed (" + elapsedMs
                        + "ms) — the timeout path was not exercised");
        assertTrue(elapsedMs < 6_000,
                () -> "stop() should force shutdown after ~1s, took " + elapsedMs + "ms");
        assertFalse(runtime.isRunning());
    }
}
