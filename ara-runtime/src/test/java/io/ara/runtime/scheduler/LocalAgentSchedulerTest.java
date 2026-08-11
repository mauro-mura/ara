package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentFuture;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LocalAgentSchedulerTest {

    private AgentRegistry       registry;
    private LocalAgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry  = new AgentRegistry();
        scheduler = new LocalAgentScheduler(registry);
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void register_inactive_schedule_does_not_fire() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        registry.register(countingAgent("a", calls));

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s1")
                .agentId(AgentId.of("a"))
                .every(Duration.ofMillis(100))
                .withInput("run")
                .active(false)
                .build());

        Thread.sleep(350);
        assertEquals(0, calls.get(), "inactive schedule must not fire");
    }

    @Test
    void register_active_schedule_fires_periodically() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        registry.register(latchAgent("b", latch));

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s2")
                .agentId(AgentId.of("b"))
                .every(Duration.ofMillis(100))
                .withInput("go")
                .build());

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "expected 3 firings within 2 seconds");
    }

    @Test
    void registering_same_id_replaces_previous() throws InterruptedException {
        AtomicInteger first  = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        registry.register(countingAgent("c1", first));
        registry.register(countingAgent("c2", second));

        scheduler.register(AgentSchedule.builder()
                .scheduleId("dup")
                .agentId(AgentId.of("c1"))
                .every(Duration.ofMillis(100))
                .withInput("x")
                .build());

        // replace with a different agent
        scheduler.register(AgentSchedule.builder()
                .scheduleId("dup")
                .agentId(AgentId.of("c2"))
                .every(Duration.ofMillis(100))
                .withInput("x")
                .build());

        Thread.sleep(400);
        assertEquals(0, first.get(),  "replaced schedule must not fire");
        assertTrue(second.get() > 0,  "new schedule must fire");
    }

    // ── pause / resume ────────────────────────────────────────────────────────

    @Test
    void pause_stops_firing() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        registry.register(countingAgent("d", calls));

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s3")
                .agentId(AgentId.of("d"))
                .every(Duration.ofMillis(100))
                .withInput("x")
                .build());

        Thread.sleep(250);
        int countBefore = calls.get();
        assertTrue(countBefore > 0);

        scheduler.pause("s3");
        Thread.sleep(300);

        assertEquals(countBefore, calls.get(), "no new firings after pause");
    }

    @Test
    void resume_restarts_firing() throws InterruptedException {
        CountDownLatch afterResume = new CountDownLatch(1);
        AtomicInteger  calls       = new AtomicInteger();
        registry.register(new StubAgent(AgentId.of("e")) {
            @Override public AgentResponse execute(AgentTask task) {
                calls.incrementAndGet();
                afterResume.countDown();
                return success(task);
            }
        });

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s4")
                .agentId(AgentId.of("e"))
                .every(Duration.ofMillis(100))
                .withInput("x")
                .build());

        scheduler.pause("s4");
        Thread.sleep(200);
        int countAfterPause = calls.get();

        scheduler.resume("s4");
        assertTrue(afterResume.await(2, TimeUnit.SECONDS), "should fire after resume");
        assertTrue(calls.get() > countAfterPause);
    }

    @Test
    void pause_unknown_id_throws() {
        assertThrows(NoSuchElementException.class, () -> scheduler.pause("ghost"));
    }

    @Test
    void resume_unknown_id_throws() {
        assertThrows(NoSuchElementException.class, () -> scheduler.resume("ghost"));
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_stops_firing_and_removes_from_list() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        registry.register(countingAgent("f", calls));

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s5")
                .agentId(AgentId.of("f"))
                .every(Duration.ofMillis(100))
                .withInput("x")
                .build());

        Thread.sleep(150);
        scheduler.cancel("s5");
        int countAtCancel = calls.get();

        Thread.sleep(300);
        assertEquals(countAtCancel, calls.get(), "no new firings after cancel");
        assertTrue(scheduler.list().stream()
                .noneMatch(s -> "s5".equals(s.scheduleId())), "should be removed from list");
    }

    @Test
    void cancel_unknown_id_throws() {
        assertThrows(NoSuchElementException.class, () -> scheduler.cancel("ghost"));
    }

    // ── triggerNow ────────────────────────────────────────────────────────────

    @Test
    void trigger_now_fires_immediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        registry.register(latchAgent("g", latch));

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s6")
                .agentId(AgentId.of("g"))
                .every(Duration.ofHours(24))   // would never fire on its own
                .withInput("now")
                .build());

        AgentFuture future = scheduler.triggerNow("s6");
        assertTrue(latch.await(2, TimeUnit.SECONDS), "triggerNow must fire agent");
        assertNotNull(future);
    }

    @Test
    void trigger_now_unknown_id_throws() {
        assertThrows(NoSuchElementException.class, () -> scheduler.triggerNow("ghost"));
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void list_returns_all_registered_schedules() {
        registry.register(new StubAgent(AgentId.of("h1")));
        registry.register(new StubAgent(AgentId.of("h2")));

        scheduler.register(AgentSchedule.builder()
                .scheduleId("listA").agentId(AgentId.of("h1"))
                .every(Duration.ofHours(1)).build());
        scheduler.register(AgentSchedule.builder()
                .scheduleId("listB").agentId(AgentId.of("h2"))
                .every(Duration.ofHours(1)).build());

        List<AgentSchedule> list = scheduler.list();
        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(s -> "listA".equals(s.scheduleId())));
        assertTrue(list.stream().anyMatch(s -> "listB".equals(s.scheduleId())));
    }

    // ── agent not found ───────────────────────────────────────────────────────

    @Test
    void firing_with_missing_agent_does_not_throw() throws InterruptedException {
        // schedule references an agent not in registry — should log warning, not crash
        scheduler.register(AgentSchedule.builder()
                .scheduleId("s7")
                .agentId(AgentId.of("nonexistent"))
                .every(Duration.ofMillis(100))
                .withInput("x")
                .build());

        Thread.sleep(250);
        // if we reach here, no exception was thrown
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    private static AraAgent countingAgent(String id, AtomicInteger counter) {
        return new StubAgent(AgentId.of(id)) {
            @Override public AgentResponse execute(AgentTask task) {
                counter.incrementAndGet();
                return success(task);
            }
        };
    }

    private static AraAgent latchAgent(String id, CountDownLatch latch) {
        return new StubAgent(AgentId.of(id)) {
            @Override public AgentResponse execute(AgentTask task) {
                latch.countDown();
                return success(task);
            }
        };
    }

    static class StubAgent implements AraAgent {
        private final AgentId agentId;

        StubAgent(AgentId agentId) { this.agentId = agentId; }

        @Override public AgentId    agentId()      { return agentId; }
        @Override public AgentConfig config()       { return AgentConfig.defaults().agentId(agentId).agentType("stub").build(); }
        @Override public AgentState  currentState() { return AgentState.IDLE; }
        @Override public void        terminate()    {}

        @Override public AgentResponse execute(AgentTask task) { return success(task); }

        AgentResponse success(AgentTask task) {
            return AgentResponse.success(
                    task.taskId(), agentId, "ok", 1, 0, 0,
                    Duration.ofMillis(1), List.of());
        }
    }

}
