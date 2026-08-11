package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AgentSchedule.Trigger;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AgentScheduleTest {

    private final AgentId agentId = AgentId.of("test-agent");

    // ── Builder ───────────────────────────────────────────────────────────────

    @Test
    void build_with_interval_trigger() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s1")
                .agentId(agentId)
                .every(Duration.ofMinutes(5))
                .withInput("run")
                .build();

        assertEquals("s1", s.scheduleId());
        assertEquals(agentId, s.agentId());
        assertEquals("run", s.inputTemplate());
        assertTrue(s.active());
        assertInstanceOf(Trigger.Interval.class, s.trigger());
        assertEquals(Duration.ofMinutes(5), ((Trigger.Interval) s.trigger()).every());
    }

    @Test
    void build_with_cron_trigger() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s2")
                .agentId(agentId)
                .cron("0 9 * * MON-FRI")
                .withInput("morning-run")
                .build();

        assertInstanceOf(Trigger.Cron.class, s.trigger());
        assertEquals("0 9 * * MON-FRI", ((Trigger.Cron) s.trigger()).expression());
    }

    @Test
    void inactive_schedule_is_preserved() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s3")
                .agentId(agentId)
                .every(Duration.ofHours(1))
                .withInput("input")
                .active(false)
                .build();

        assertFalse(s.active());
    }

    @Test
    void empty_input_template_is_allowed() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s4")
                .agentId(agentId)
                .every(Duration.ofSeconds(30))
                .build();

        assertEquals("", s.inputTemplate());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void missing_scheduleId_throws() {
        assertThrows(NullPointerException.class, () ->
                AgentSchedule.builder()
                        .agentId(agentId)
                        .every(Duration.ofMinutes(1))
                        .build());
    }

    @Test
    void blank_scheduleId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentSchedule.builder()
                        .scheduleId("  ")
                        .agentId(agentId)
                        .every(Duration.ofMinutes(1))
                        .build());
    }

    @Test
    void missing_agentId_throws() {
        assertThrows(NullPointerException.class, () ->
                AgentSchedule.builder()
                        .scheduleId("s5")
                        .every(Duration.ofMinutes(1))
                        .build());
    }

    @Test
    void missing_trigger_throws() {
        assertThrows(NullPointerException.class, () ->
                AgentSchedule.builder()
                        .scheduleId("s6")
                        .agentId(agentId)
                        .build());
    }

    @Test
    void zero_interval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Trigger.Interval(Duration.ZERO));
    }

    @Test
    void negative_interval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Trigger.Interval(Duration.ofSeconds(-1)));
    }

    @Test
    void blank_cron_expression_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Trigger.Cron("   "));
    }

    // ── CronEvaluator ─────────────────────────────────────────────────────────

    @Test
    void cron_wildcard_returns_positive_delay() {
        long delay = LocalAgentScheduler.CronEvaluator.secondsUntilNext("* * * * *");
        assertTrue(delay >= 1, "delay should be at least 1 second, was: " + delay);
        assertTrue(delay <= 60, "wildcard cron should fire within 60 seconds");
    }

    @Test
    void cron_wrong_field_count_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 9 *"));
    }

    @Test
    void cron_out_of_range_minute_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("99 * * * *"));
    }

    @Test
    void cron_dow_range_never_lands_on_weekend() {
        // Regression test: MON-FRI was previously parsed to a sentinel that the
        // matcher treated as wildcard ("any day"), so weekend runs slipped through.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 9 * * MON-FRI");
        java.time.DayOfWeek day = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds)
                .getDayOfWeek();
        assertTrue(day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY,
                "MON-FRI cron must never resolve to a weekend day, got: " + day);
    }

    @Test
    void cron_single_dow_matches_only_that_day() {
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 * * WED");
        // secondsUntilNext truncates to whole seconds (ChronoUnit.SECONDS.between), so when the
        // target is an exact minute boundary (00:00) the result can under-shoot by up to ~1s —
        // negligible for scheduling, but enough to land just before midnight on the day before.
        // +1s re-crosses that boundary without risking crossing into the following day.
        java.time.DayOfWeek day = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds + 1)
                .getDayOfWeek();
        assertEquals(java.time.DayOfWeek.WEDNESDAY, day);
    }
}
