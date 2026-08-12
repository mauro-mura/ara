package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static io.ara.runtime.pipeline.PipelineTestAgents.failingAgent;
import static org.junit.jupiter.api.Assertions.*;

class ParallelAgentTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void fansOutToEveryMember_andMergesSuccesses() {
        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"),
                List.of(echoAgent("a", "A"), echoAgent("b", "B"), echoAgent("c", "C")),
                executor, AgentChain.MergeStrategy.joining(","));

        AgentResponse response = parallel.execute(AgentTask.of("go"));

        assertTrue(response.isSuccess());
        // joining() preserves member declaration order, not completion order.
        assertEquals("A,B,C", response.content());
    }

    @Test
    void membersRunConcurrently_notSequentially() throws InterruptedException {
        int memberCount = 4;
        CountDownLatch allStarted = new CountDownLatch(memberCount);
        CountDownLatch release = new CountDownLatch(1);

        List<AraAgent> members = java.util.stream.IntStream.range(0, memberCount)
                .mapToObj(i -> blockingAgent("m" + i, allStarted, release))
                .map(AraAgent.class::cast)
                .toList();

        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"), members,
                executor, AgentChain.MergeStrategy.joining(","));

        Thread runner = new Thread(() -> parallel.execute(AgentTask.of("go")));
        runner.start();

        // If members ran sequentially, this would time out waiting for member 2..N to even
        // start while member 0 sits blocked on `release`.
        boolean allReachedTheBarrierConcurrently = allStarted.await(5, TimeUnit.SECONDS);
        release.countDown();
        runner.join(5000);

        assertTrue(allReachedTheBarrierConcurrently,
                "all members should have started before any of them was released");
    }

    @Test
    void allMembersReceiveTheIdenticalTask() {
        Set<String> seenInputs = new CopyOnWriteArraySet<>();
        AraAgent capturer1 = capturingAgent("cap1", seenInputs);
        AraAgent capturer2 = capturingAgent("cap2", seenInputs);

        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"),
                List.of(capturer1, capturer2), executor, AgentChain.MergeStrategy.joining(","));

        parallel.execute(AgentTask.of("shared-input"));

        assertEquals(Set.of("shared-input"), seenInputs);
    }

    @Test
    void failFast_isDefaultFailurePolicy_oneFailureFailsTheWhole() {
        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"),
                List.of(echoAgent("ok", "fine"), failingAgent("bad", "boom")),
                executor, AgentChain.MergeStrategy.joining(","));

        AgentResponse response = parallel.execute(AgentTask.of("go"));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReason().contains("boom"));
    }

    @Test
    void partialOk_mergesOnlyTheSuccesses() {
        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"),
                List.of(echoAgent("ok1", "A"), failingAgent("bad", "boom"), echoAgent("ok2", "B")),
                executor, AgentChain.MergeStrategy.joining(","), AgentChain.FailurePolicy.PARTIAL_OK);

        AgentResponse response = parallel.execute(AgentTask.of("go"));

        assertTrue(response.isSuccess());
        assertEquals("A,B", response.content());
    }

    @Test
    void terminate_isBestEffort_oneMemberThrowingDoesNotStopTheRest() {
        AtomicBoolean secondTerminated = new AtomicBoolean(false);
        AraAgent throwsOnTerminate = new AraAgent() {
            final AgentId id = AgentId.of("throws");
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, "x", 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() { throw new RuntimeException("cannot terminate"); }
        };
        AraAgent tracksTermination = new AraAgent() {
            final AgentId id = AgentId.of("tracks");
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, "x", 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() { secondTerminated.set(true); }
        };

        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"),
                List.of(throwsOnTerminate, tracksTermination), executor, AgentChain.MergeStrategy.joining(","));

        assertDoesNotThrow(() -> parallel.terminate());
        assertTrue(secondTerminated.get());
    }

    @Test
    void currentState_alwaysIdle_neverGatesConcurrentCalls() throws InterruptedException {
        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"),
                List.of(echoAgent("a", "A")), executor, AgentChain.MergeStrategy.joining(","));

        int concurrentCalls = 8;
        CountDownLatch done = new CountDownLatch(concurrentCalls);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < concurrentCalls; i++) {
            executor.submit(() -> {
                try {
                    parallel.execute(AgentTask.of("go"));
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "no concurrent call should be rejected for 'not IDLE'");
        assertEquals(AgentState.IDLE, parallel.currentState());
    }

    @Test
    void defaultConstructor_stillBuildsTheDefaultParallelConfig() {
        // Regression guard: adding the explicit-AgentConfig overload must not change
        // what the existing constructors build on their own.
        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"),
                List.of(echoAgent("a", "A")), executor, AgentChain.MergeStrategy.joining(","));

        assertEquals("parallel", parallel.config().agentType());
        assertEquals(AgentId.of("fanout"), parallel.config().agentId());
    }

    @Test
    void explicitConfig_isUsedVerbatim_insteadOfTheBuiltInDefault() {
        AgentConfig config = AgentConfig.defaults()
                .agentId(AgentId.of("fanout"))
                .agentType("custom-fanout")
                .build();

        ParallelAgent parallel = new ParallelAgent(AgentId.of("fanout"), config,
                List.of(echoAgent("a", "A")), executor, AgentChain.MergeStrategy.joining(","));

        assertSame(config, parallel.config());
        assertEquals("custom-fanout", parallel.config().agentType());

        AgentResponse response = parallel.execute(AgentTask.of("go"));
        assertTrue(response.isSuccess(), "an explicit config must not otherwise change fan-out behavior");
        assertEquals("A", response.content());
    }

    @Test
    void rejectsEmptyMemberList() {
        assertThrows(IllegalArgumentException.class, () ->
                new ParallelAgent(AgentId.of("fanout"), List.of(), executor,
                        AgentChain.MergeStrategy.joining(",")));
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    private static AraAgent capturingAgent(String id, Set<String> seenInputs) {
        AgentId agentId = AgentId.of(id);
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                seenInputs.add(task.input());
                return AgentResponse.success(task.taskId(), agentId, "ok", 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }

    /** Counts down {@code arrived} on entry, then blocks until {@code release} opens. */
    private static AraAgent blockingAgent(String id, CountDownLatch arrived, CountDownLatch release) {
        AgentId agentId = AgentId.of(id);
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                arrived.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AgentResponse.success(task.taskId(), agentId, "done", 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }
}
