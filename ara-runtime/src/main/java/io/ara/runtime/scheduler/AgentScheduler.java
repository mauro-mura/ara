package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentFuture;
import io.ara.core.agent.AgentSchedule;

import java.util.List;

/**
 * Manages the lifecycle of {@link AgentSchedule} instances and fires their
 * associated agents according to the configured trigger (ADR-032).
 *
 * <p>Obtained via {@code AraRuntime.scheduler()}. Two implementations exist:
 * <ul>
 *   <li>{@link LocalAgentScheduler}  — single-JVM, in-memory</li>
 *   <li>{@code DistributedAgentScheduler} — DB-pull with {@code SELECT FOR UPDATE
 *       SKIP LOCKED} (ara-cluster, ADR-031/ADR-032)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AgentSchedule schedule = AgentSchedule.builder()
 *     .scheduleId("daily-report")
 *     .agentId(agent.id())
 *     .every(Duration.ofHours(24))
 *     .withInput("Genera il report giornaliero")
 *     .build();
 *
 * runtime.scheduler().register(schedule);
 * }</pre>
 */
public interface AgentScheduler {

    /**
     * Registers a schedule. If a schedule with the same {@code scheduleId}
     * already exists it is replaced.
     *
     * <p>If {@code schedule.active()} is {@code false} the schedule is stored
     * but not started.
     */
    void register(AgentSchedule schedule);

    /**
     * Temporarily suspends execution of the schedule identified by
     * {@code scheduleId}. The schedule definition is preserved and can be
     * resumed later with {@link #resume(String)}.
     *
     * @throws java.util.NoSuchElementException if the schedule is not registered
     */
    void pause(String scheduleId);

    /**
     * Resumes a previously paused schedule.
     *
     * @throws java.util.NoSuchElementException if the schedule is not registered
     */
    void resume(String scheduleId);

    /**
     * Cancels and removes the schedule. After this call the schedule no longer
     * exists; use {@link #register(AgentSchedule)} to re-add it.
     *
     * @throws java.util.NoSuchElementException if the schedule is not registered
     */
    void cancel(String scheduleId);

    /**
     * Triggers an immediate one-shot execution of the schedule, regardless of
     * its next scheduled fire time.
     *
     * @return an {@link AgentFuture} tracking the execution
     * @throws java.util.NoSuchElementException if the schedule is not registered
     */
    AgentFuture triggerNow(String scheduleId);

    /** Returns all currently registered schedules. */
    List<AgentSchedule> list();

    /** Starts the internal scheduling machinery. Called by {@code AraRuntime.start()}. */
    void start();

    /** Stops all scheduled jobs and releases resources. Called by {@code AraRuntime.stop()}. */
    void stop();
}
