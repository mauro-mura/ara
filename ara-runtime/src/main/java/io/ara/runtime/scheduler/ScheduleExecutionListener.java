package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentResponse;
import io.ara.core.common.AgentId;

/**
 * Observes the lifecycle of scheduler-triggered agent executions (ADR-032).
 *
 * <p>A single optional listener can be attached to the runtime via
 * {@code AraRuntime.Builder#scheduleExecutionListener(ScheduleExecutionListener)}.
 * It receives every fire the {@link LocalAgentScheduler} performs — regardless of
 * whether it came from a periodic trigger or an explicit {@link AgentScheduler#triggerNow} —
 * and the outcome of each execution. Typical uses are application-level observability
 * (telemetry, {@code last_run} bookkeeping on a schedule store) and audit trails; the
 * scheduler itself stays persistence-agnostic.
 *
 * <p><b>Contract.</b> Both callbacks are best-effort:
 * <ul>
 *   <li>{@link #onFire} runs on the scheduler's tick thread — keep it lightweight and
 *       non-blocking, draining a tick thread delays every other schedule's trigger.</li>
 *   <li>{@link #onComplete} runs on the executor thread that ran the agent (a virtual
 *       thread), so it may do modest work such as a store update.</li>
 *   <li>Exceptions thrown by either callback are caught and logged by the scheduler and
 *       <em>never</em> propagate: a faulty listener cannot break or kill a schedule.</li>
 * </ul>
 *
 * <p>Both methods are {@code default} no-ops so implementations only override what they
 * need.
 */
public interface ScheduleExecutionListener {

    /**
     * Invoked just before the agent for {@code scheduleId} is dispatched. Called for
     * every fire, including the case where the target agent is not registered (the
     * {@code onComplete} callback then reports the failure response).
     */
    default void onFire(String scheduleId, AgentId agentId) {
    }

    /**
     * Invoked once the dispatched execution finishes — successfully or not. The
     * {@code response} is never {@code null}: a failed or exceptionally-thrown execution
     * is reported as an {@link AgentResponse#failure} synthesized by the scheduler.
     *
     * @param scheduleId the schedule that fired
     * @param response   the terminal response of the run (success or failure), never null
     */
    default void onComplete(String scheduleId, AgentResponse response) {
    }
}