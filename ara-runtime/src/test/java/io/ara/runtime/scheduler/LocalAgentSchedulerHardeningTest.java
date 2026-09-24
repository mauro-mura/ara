package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AgentTask;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P1/P2, §4 U7/U8 — regression tests for
 * {@link LocalAgentScheduler}.
 */
class LocalAgentSchedulerHardeningTest {

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

    // ── U7: a throwing trigger must not silently kill the schedule ─────────────

    /**
     * {@code fire()} throws synchronously when {@code inputTemplate} is blank — {@code
     * AgentTask.of("")} rejects blank input with no media — a real, easily reached case (a
     * schedule registered without ever calling {@code withInput(...)}, which defaults to
     * {@code ""}), not a contrived one. Before U7 this exception, thrown from inside a
     * {@code scheduleAtFixedRate} task, would silently cancel every future execution of that
     * periodic task (a documented {@code ScheduledThreadPoolExecutor} pitfall) — calling
     * {@link LocalAgentScheduler#safeFire} directly, repeatedly, is the only way to assert
     * "never propagates" deterministically; proving the same through the real {@code
     * scheduleAtFixedRate} machinery would only be provable indirectly and non-deterministically.
     */
    @Test
    void safeFire_neverPropagates_whenFireThrowsSynchronously() {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("blank-input-agent")));
        AgentSchedule blankInputSchedule = AgentSchedule.builder()
                .scheduleId("blank")
                .agentId(AgentId.of("blank-input-agent"))
                .every(Duration.ofMillis(100))
                // no withInput(...) — inputTemplate defaults to "", AgentTask.of("") throws
                .build();

        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> scheduler.safeFire(blankInputSchedule),
                    "safeFire must swallow fire()'s exception on every call, not just the first");
        }
    }

    /**
     * Integration-level companion: registering (and leaving active) a schedule whose every
     * trigger throws must not itself throw, and the schedule must remain listed — the
     * scheduler as a whole must keep running rather than being poisoned by one bad schedule.
     */
    @Test
    void repeatingScheduleWithABlankInput_staysRegistered_acrossSeveralTicks() throws InterruptedException {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("blank-input-agent-2")));

        assertDoesNotThrow(() -> scheduler.register(AgentSchedule.builder()
                .scheduleId("blank2")
                .agentId(AgentId.of("blank-input-agent-2"))
                .every(Duration.ofMillis(50))
                .build()));

        Thread.sleep(400);   // several ticks' worth of time — each one throws internally

        assertTrue(scheduler.list().stream().anyMatch(s -> "blank2".equals(s.scheduleId())),
                "a schedule whose every trigger throws must remain registered, not be silently dropped");
    }

    // ── U8: agent execution must not starve scheduling ticks ───────────────────

    /**
     * Before U8, {@code tickExecutor} and {@code agentExecutor} were the same fixed-size pool
     * (sized {@code max(2, availableProcessors())}). Saturating it with more concurrent
     * long-running agent executions than its size left nothing to fire the next tick on time.
     */
    @Test
    void aBurstOfLongRunningAgentExecutions_doesNotDelayAnotherSchedulesTick() throws InterruptedException {
        int cpus = Runtime.getRuntime().availableProcessors();
        int hogCount = cpus + 2;   // enough to have saturated the old shared fixed-size pool

        CountDownLatch hogsStarted = new CountDownLatch(hogCount);
        CountDownLatch releaseHogs = new CountDownLatch(1);
        for (int i = 0; i < hogCount; i++) {
            AgentId id = AgentId.of("hog-" + i);
            registry.register(new LocalAgentSchedulerTest.StubAgent(id) {
                @Override public AgentResponse execute(AgentTask task) {
                    hogsStarted.countDown();
                    try {
                        releaseHogs.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return success(task);
                }
            });
            scheduler.register(AgentSchedule.builder()
                    .scheduleId("hog-schedule-" + i)
                    .agentId(id)
                    .every(Duration.ofMillis(50))
                    .withInput("go")
                    .build());
        }

        try {
            assertTrue(hogsStarted.await(5, TimeUnit.SECONDS),
                    "hogs never started — cannot exercise the starvation scenario");

            // Registered only after the hogs have already saturated whatever pool backs
            // agent execution — before U8, sharing the tick pool meant this could never fire
            // until a hog released its worker.
            CountDownLatch fastFired = new CountDownLatch(1);
            registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("fast")) {
                @Override public AgentResponse execute(AgentTask task) {
                    fastFired.countDown();
                    return success(task);
                }
            });
            scheduler.register(AgentSchedule.builder()
                    .scheduleId("fast-schedule")
                    .agentId(AgentId.of("fast"))
                    .every(Duration.ofMillis(50))
                    .withInput("go")
                    .build());

            assertTrue(fastFired.await(3, TimeUnit.SECONDS),
                    "a schedule's tick must not be starved by long-running agent executions "
                            + "occupying the pool (U8: ticks and agent execution use separate executors)");
        } finally {
            releaseHogs.countDown();
        }
    }

    // ── P2 (unassigned, fixed alongside U8): register() race no longer orphans a job ──

    /**
     * {@code register}/{@code pause}/{@code resume} used to read {@code entries.get(id)} and
     * separately {@code entries.put(id, ...)} — two non-atomic map operations. Two concurrent
     * {@code register} calls for the same id could both observe the same (or no) starting
     * entry, each start their own {@link java.util.concurrent.ScheduledFuture}, and then
     * overwrite each other's map entry — whichever {@code put} landed last would win, silently
     * orphaning the other call's job, which would keep firing forever, unreachable from {@code
     * list}/{@code pause}/{@code cancel}. Fixed via {@code entries.compute}, which {@link
     * java.util.concurrent.ConcurrentHashMap} serializes per id.
     */
    @Test
    void concurrentRegisterCallsForTheSameId_neverLeaveAnOrphanedDuplicateJob() throws InterruptedException {
        AtomicInteger fireCount = new AtomicInteger();
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("racer")) {
            @Override public AgentResponse execute(AgentTask task) {
                fireCount.incrementAndGet();
                return success(task);
            }
        });

        int racers = 8;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                scheduler.register(AgentSchedule.builder()
                        .scheduleId("race")
                        .agentId(AgentId.of("racer"))
                        .every(Duration.ofMillis(50))
                        .withInput("go")
                        .build());
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(5));
        }

        Thread.sleep(300);   // let whichever job(s) exist fire a few times
        assertEquals(1, scheduler.list().stream().filter(s -> "race".equals(s.scheduleId())).count(),
                "exactly one schedule must remain registered under the id, however many callers raced");

        scheduler.cancel("race");
        int countAtCancel = fireCount.get();
        Thread.sleep(400);   // several more intervals' worth of time

        assertEquals(countAtCancel, fireCount.get(),
                "no firing after cancel() — an orphaned duplicate job from the race would still be firing here");
    }

    // ── P2 (cron self-reschedule): pause() racing scheduleCron's own reschedule step ──

    /**
     * {@link LocalAgentScheduler#rescheduleCronAfterFire} used to read {@code entries.get(id)}
     * and separately {@code entries.put(id, ...)} — the identical get-then-put race already
     * fixed for {@code register}/{@code pause}/{@code resume} above, but reachable here too: a
     * {@code pause()} landing between the read and the write could be clobbered by the
     * reschedule's {@code put()}, resurrecting a schedule that had just been turned off. Fixed
     * via {@code entries.compute}. A real cron's reschedule step only runs after a delay of up
     * to a minute ({@link LocalAgentScheduler.CronEvaluator}), so this races the extracted,
     * package-private {@code rescheduleCronAfterFire} directly instead of waiting on real cron
     * timing — deterministic and fast, same testability reasoning as {@code safeFire} above.
     *
     * <p>With the fix, {@code entries.compute} serializes {@code pause} and {@code
     * rescheduleCronAfterFire} for the same id: whichever runs first is always fully observed
     * by the other, so no matter which wins the race, a {@code pause()} that ran at all must
     * leave the schedule paused once both have completed — {@code rescheduleCronAfterFire}
     * either sees the pause and declines to resurrect it, or runs first and installs a future
     * that the subsequent {@code pause()} then correctly cancels.
     */
    @Test
    void cronReschedule_racingPause_neverResurrectsAPausedSchedule() throws InterruptedException {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("cron-racer")));
        // "every minute" so CronEvaluator's scan is O(1) (200 rounds re-run it) — the real
        // ~1-60s one-shot future it schedules never actually fires within this test's lifetime.
        String cronExpression = "* * * * *";
        AgentSchedule schedule = AgentSchedule.builder()
                .scheduleId("cron-race")
                .agentId(AgentId.of("cron-racer"))
                .cron(cronExpression)
                .withInput("go")
                .build();
        scheduler.register(schedule);

        for (int round = 0; round < 200; round++) {
            CountDownLatch go = new CountDownLatch(1);
            Thread pauser = Thread.ofVirtual().start(() -> {
                await(go);
                scheduler.pause("cron-race");
            });
            Thread rescheduler = Thread.ofVirtual().start(() -> {
                await(go);
                scheduler.rescheduleCronAfterFire(schedule, cronExpression);
            });
            go.countDown();
            pauser.join(TimeUnit.SECONDS.toMillis(5));
            rescheduler.join(TimeUnit.SECONDS.toMillis(5));

            assertTrue(scheduler.isPaused("cron-race"),
                    "round " + round + ": pause() ran, so the schedule must end up paused "
                            + "regardless of interleaving with the concurrent reschedule");

            scheduler.resume("cron-race"); // reset for the next round
        }
    }

    // ── list() must report the live paused/active state, not the registered definition ──

    private boolean listedAsActive(String scheduleId) {
        return scheduler.list().stream()
                .filter(s -> scheduleId.equals(s.scheduleId()))
                .findFirst().orElseThrow()
                .active();
    }

    /**
     * {@code pause()} cancels the job but used to leave the stored {@link AgentSchedule}
     * untouched, so {@code list()} kept reporting {@code active=true} for a schedule that would
     * never fire — a caller (e.g. an agent tool) that paused a schedule and then listed it saw
     * it as still active.
     */
    @Test
    void list_reflectsPauseAndResume() {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("pausable")));
        scheduler.register(AgentSchedule.builder()
                .scheduleId("pausable-schedule")
                .agentId(AgentId.of("pausable"))
                .every(Duration.ofHours(1))
                .withInput("go")
                .build());
        assertTrue(listedAsActive("pausable-schedule"), "freshly registered schedule is active");

        scheduler.pause("pausable-schedule");
        assertFalse(listedAsActive("pausable-schedule"), "a paused schedule must be listed as inactive");

        scheduler.resume("pausable-schedule");
        assertTrue(listedAsActive("pausable-schedule"), "a resumed schedule must be listed as active again");
    }

    @Test
    void list_reportsARegisteredInactiveScheduleAsInactive_andResumeActivatesIt() {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("dormant")));
        scheduler.register(AgentSchedule.builder()
                .scheduleId("dormant-schedule")
                .agentId(AgentId.of("dormant"))
                .every(Duration.ofHours(1))
                .withInput("go")
                .active(false)
                .build());
        assertFalse(listedAsActive("dormant-schedule"));

        scheduler.resume("dormant-schedule");
        assertTrue(listedAsActive("dormant-schedule"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
