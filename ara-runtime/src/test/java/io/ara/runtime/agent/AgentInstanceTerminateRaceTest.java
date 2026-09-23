package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.strategy.ExecutionPlanner;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.wiring.AgentWiring;
import io.ara.runtime.wiring.Lease;
import io.ara.runtime.wiring.WiringFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 N5 row (AgentInstance TOCTOU), §4
 * U18bis — regression test.
 *
 * <p><b>Correction made while writing this test:</b> a task racing a concurrent {@code
 * terminate()} was already prevented from actually <em>running</em> — {@code runStrategy}
 * independently re-checks {@code closed}/{@code isCancelRequested()} right before invoking
 * the strategy, well after {@code getOrCreate}. The real gap U18bis closes is narrower than
 * "a task might run against a terminated agent": it is a <em>resource leak</em>. A session
 * created (or, as here, rebuilt after {@code shutdown()}'s drain tore down the in-flight
 * first attempt — see {@code SessionEntry.takeForClose()}, U24) strictly after {@code
 * terminate()}'s one-time {@code sessionManager.shutdown()} drain already ran is invisible
 * to that drain — nothing else will ever close its wiring, since {@code terminate()} is
 * idempotent and {@code execute()}'s very first check now rejects every subsequent call
 * before it ever reaches {@code getOrCreate} again. {@code handleEarlyTermination} (the
 * path {@code runStrategy}'s own check takes) does not close the session either — it only
 * resets state for reuse, which is correct for its actual callers (a task cancelled or
 * queued mid-flight on a session that stays alive) but would leave this one orphaned,
 * still registered, wiring open, forever. This test's real assertions are therefore the
 * session-count and release-count checks below, not the failure message, which may
 * legitimately come from either checkpoint.
 */
class AgentInstanceTerminateRaceTest {

    private static AgentConfig dummyConfig() {
        return AgentConfig.defaults().agentType("t").primaryLlm(LlmProfile.of("m"))
                .plannerStrategy("react").build();
    }

    /** A {@link WiringFactory} whose build blocks until released, so a test can land a teardown while it is in flight. */
    private static final class GatedWiringFactory implements WiringFactory {
        final CountDownLatch buildStarted = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final AtomicInteger builds = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        @Override
        public AgentWiring build(AgentConfig config) {
            builds.incrementAndGet();
            buildStarted.countDown();
            try {
                assertTrue(proceed.await(10, TimeUnit.SECONDS), "test must release the build");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LlmClient neverCalled = new LlmClient() {
                @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                    throw new UnsupportedOperationException("must never actually run — the task must be rejected first");
                }
                @Override public String providerId() { return "noop"; }
            };
            Lease<Object> lease = new Lease<>(new Object(), releases::incrementAndGet);
            return new AgentWiring(config, neverCalled, ToolRegistry.empty(), List.of(lease));
        }
    }

    private static ExecutionPlanner neverRunPlanner() {
        return ExecutionPlanner.builder()
                .register(new io.ara.core.agent.ExecutionStrategy() {
                    @Override
                    public ExecutionResult execute(AgentTask task, LlmClient llm, MemoryManager memory,
                                                    ToolRegistry tools, AgentConfig config) {
                        throw new UnsupportedOperationException("must never actually run — the task must be rejected first");
                    }
                    @Override public String strategyName() { return "react"; }
                })
                .build();
    }

    @Test
    void terminate_racingAnInFlightGetOrCreate_isCaughtByExecute_noSessionLeaksItsWiring() throws Exception {
        GatedWiringFactory wiringFactory = new GatedWiringFactory();
        AgentInstance instance = new AgentInstance(
                dummyConfig(), wiringFactory, sid -> new InMemoryMemoryManager(),
                neverRunPlanner(), AgentInterceptorChain.empty(), AraTelemetry.noop(), SessionStore.noop());

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<AgentResponse> executeFuture = executor.submit(() ->
                    instance.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1"))));
            assertTrue(wiringFactory.buildStarted.await(5, TimeUnit.SECONDS), "getOrCreate's build must have started");

            // terminate() itself blocks here: its own sessionManager.shutdown() drain finds
            // this session's placeholder and parks on the same buildLock the build above
            // holds (SessionEntry.takeForClose(), U24) — so it must run on its own thread.
            Future<?> terminateFuture = executor.submit(() -> { instance.terminate(); return null; });
            // Same sleep-based synchronization as SessionManagerConcurrencyTest's own
            // analogous test (invalidate_racingAnInFlightFirstBuild_...): there is no
            // public seam to poll "has takeForClose() reached buildLock yet" instead.
            // Margin kept generous (well over the 5s countDown/get timeouts below would
            // tolerate anyway) since a full-reactor run under heavy load has been observed
            // to need more than a bare 200ms here.
            Thread.sleep(500);   // let terminate() actually reach and park on buildLock

            wiringFactory.proceed.countDown();   // let the (now torn-down) first build finish

            terminateFuture.get(5, TimeUnit.SECONDS);
            AgentResponse response = executeFuture.get(5, TimeUnit.SECONDS);

            assertFalse(response.isSuccess(), "the task must not succeed against a terminated agent");
            assertEquals(0, instance.activeSessionCount(),
                    "no session created during the terminate() race may remain registered");
            // getOrCreate retries after its first build is torn down mid-flight (U24), so
            // this races two builds — the first, torn down while still building, and the
            // second, torn down by this test's own U18bis fix after getOrCreate returns it
            // to a caller that finds `closed` already true. Every build's wiring must be
            // released; none may leak.
            assertEquals(2, wiringFactory.builds.get(),
                    "expected the U24 in-flight rebuild plus the U18bis post-getOrCreate teardown");
            assertEquals(2, wiringFactory.releases.get(),
                    "every build's wiring must be released — none may leak");
        } finally {
            executor.shutdown();
        }
    }
}
