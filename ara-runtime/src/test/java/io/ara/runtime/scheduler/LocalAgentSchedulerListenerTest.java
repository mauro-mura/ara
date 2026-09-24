package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScheduleExecutionListener} contract: every fire reports {@code onFire} once and
 * {@code onComplete} once with a never-null response — including for missing agents; listener
 * failures are swallowed and never break the schedule.
 */
class LocalAgentSchedulerListenerTest {

    private AgentRegistry       registry;
    private LocalAgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistry();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    private LocalAgentScheduler newScheduler(ScheduleExecutionListener listener) {
        scheduler = new LocalAgentScheduler(registry, listener);
        return scheduler;
    }

    @Test
    void successful_execution_reports_fire_and_complete() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<AgentResponse> responseRef = new AtomicReference<>();
        newScheduler(new ScheduleExecutionListener() {
            @Override public void onComplete(String scheduleId, AgentResponse response) {
                responseRef.set(response);
                completed.countDown();
            }
        }).start();

        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("ok")));
        scheduler.register(AgentSchedule.builder()
                .scheduleId("s-ok")
                .agentId(AgentId.of("ok"))
                .every(Duration.ofHours(24))
                .withInput("go")
                .build());

        scheduler.triggerNow("s-ok");

        assertTrue(completed.await(2, TimeUnit.SECONDS), "onComplete must fire after execution");
        assertNotNull(responseRef.get());
        assertTrue(responseRef.get().isSuccess(), "a successful run must report a success response");
    }

    @Test
    void missing_agent_reports_a_failure_response() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<AgentResponse> responseRef = new AtomicReference<>();
        newScheduler(new ScheduleExecutionListener() {
            @Override public void onComplete(String scheduleId, AgentResponse response) {
                responseRef.set(response);
                completed.countDown();
            }
        }).start();

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s-ghost")
                .agentId(AgentId.of("nobody"))
                .every(Duration.ofHours(24))
                .withInput("go")
                .build());

        scheduler.triggerNow("s-ghost");

        assertTrue(completed.await(2, TimeUnit.SECONDS),
                "onComplete must fire even when the agent is missing");
        assertNotNull(responseRef.get());
        assertFalse(responseRef.get().isSuccess(), "a missing agent must report a failure response");
    }

    @Test
    void listener_exception_on_both_callbacks_does_not_break_scheduling() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("tough")) {
            @Override public AgentResponse execute(io.ara.core.agent.AgentTask task) {
                calls.incrementAndGet();
                return success(task);
            }
        });
        newScheduler(new ScheduleExecutionListener() {
            @Override public void onFire(String scheduleId, AgentId agentId) {
                throw new IllegalStateException("onFire boom");
            }
            @Override public void onComplete(String scheduleId, AgentResponse response) {
                throw new IllegalStateException("onComplete boom");
            }
        }).start();

        scheduler.register(AgentSchedule.builder()
                .scheduleId("s-tough")
                .agentId(AgentId.of("tough"))
                .every(Duration.ofMillis(50))
                .withInput("go")
                .build());

        Thread.sleep(300);
        assertTrue(calls.get() > 0,
                "a throwing listener must not kill the schedule (fires: " + calls.get() + ")");
    }

    @Test
    void blank_input_still_reports_onFire_and_onComplete_and_schedule_survives() throws InterruptedException {
        // A periodic schedule with a blank input makes AgentTask.of throw inside fire() —
        // before any agent runs. fire() must still report a matching onComplete (a synthesized
        // failure) for every onFire, per the listener's "one onFire, one onComplete" contract,
        // and the schedule must remain registered and keep ticking.
        AtomicInteger firings = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<AgentResponse> lastFailure = new AtomicReference<>();
        newScheduler(new ScheduleExecutionListener() {
            @Override public void onFire(String scheduleId, AgentId agentId) {
                firings.incrementAndGet();
            }
            @Override public void onComplete(String scheduleId, AgentResponse response) {
                completions.incrementAndGet();
                lastFailure.set(response);
            }
        }).start();

        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("blank")));
        scheduler.register(AgentSchedule.builder()
                .scheduleId("s-blank")
                .agentId(AgentId.of("blank"))
                .every(Duration.ofMillis(50))
                .build());

        Thread.sleep(250);
        assertTrue(firings.get() > 0, "onFire must be reached on each (throwing) tick");
        // completions is incremented synchronously right after firings on every tick (both
        // happen on the same tick thread, in the same fire() call), so it can only ever trail
        // firings by an in-flight tick — never accumulate a permanent gap.
        assertTrue(completions.get() > 0, "onComplete must be reached on each (throwing) tick");
        assertTrue(completions.get() >= firings.get() - 1,
                "onComplete must track onFire one-for-one, not lag behind it");
        assertNotNull(lastFailure.get());
        assertFalse(lastFailure.get().isSuccess());
        assertEquals(1, scheduler.list().stream()
                        .filter(s -> "s-blank".equals(s.scheduleId())).count(),
                "schedule must remain registered despite throwing on each tick");
    }
}