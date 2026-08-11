package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentFuture;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AgentSchedule.Trigger;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.runtime.agent.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single-JVM implementation of {@link AgentScheduler} (ADR-032).
 *
 * <p>Uses a {@link ScheduledExecutorService} backed by virtual threads.
 * Schedules are held in-memory — they do not survive process restart.
 * For persistence and distributed coordination see {@code DistributedAgentScheduler}
 * in {@code ara-cluster} (ADR-031).
 *
 * <p>Execution is fire-and-forget: each trigger calls
 * {@link AraAgents#executeAsync(AraAgent, AgentTask, java.util.concurrent.Executor)} and
 * logs the outcome. If the agent is not found in the registry the trigger is
 * skipped with a warning.
 */
public final class LocalAgentScheduler implements AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentScheduler.class);

    private final AgentRegistry            registry;
    private final ScheduledExecutorService executor;

    /** Holds both the AgentSchedule definition and its active ScheduledFuture. */
    private record Entry(AgentSchedule schedule, ScheduledFuture<?> future, boolean paused) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    public LocalAgentScheduler(AgentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.executor = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Thread.ofVirtual().factory());
    }

    // ── AgentScheduler ────────────────────────────────────────────────────────

    @Override
    public void register(AgentSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule must not be null");

        // cancel existing entry with same id if present
        Entry existing = entries.get(schedule.scheduleId());
        if (existing != null) {
            existing.future().cancel(false);
        }

        ScheduledFuture<?> future = schedule.active()
                ? scheduleJob(schedule)
                : null;

        entries.put(schedule.scheduleId(), new Entry(schedule, future, !schedule.active()));
        log.info("[Scheduler] registered '{}' trigger={} active={}",
                schedule.scheduleId(), describetr(schedule.trigger()), schedule.active());
    }

    @Override
    public void pause(String scheduleId) {
        Entry entry = require(scheduleId);
        if (entry.paused()) return;
        entry.future().cancel(false);
        entries.put(scheduleId, new Entry(entry.schedule(), null, true));
        log.info("[Scheduler] paused '{}'", scheduleId);
    }

    @Override
    public void resume(String scheduleId) {
        Entry entry = require(scheduleId);
        if (!entry.paused()) return;
        ScheduledFuture<?> future = scheduleJob(entry.schedule());
        entries.put(scheduleId, new Entry(entry.schedule(), future, false));
        log.info("[Scheduler] resumed '{}'", scheduleId);
    }

    @Override
    public void cancel(String scheduleId) {
        Entry entry = require(scheduleId);
        if (entry.future() != null) entry.future().cancel(false);
        entries.remove(scheduleId);
        log.info("[Scheduler] cancelled '{}'", scheduleId);
    }

    @Override
    public AgentFuture triggerNow(String scheduleId) {
        AgentSchedule schedule = require(scheduleId).schedule();
        log.info("[Scheduler] triggerNow '{}'", scheduleId);
        return fire(schedule);
    }

    @Override
    public List<AgentSchedule> list() {
        return entries.values().stream()
                .map(Entry::schedule)
                .toList();
    }

    @Override
    public void start() {
        started = true;
        log.info("[Scheduler] started ({} schedule(s) registered)", entries.size());
    }

    @Override
    public void stop() {
        entries.values().forEach(e -> {
            if (e.future() != null) e.future().cancel(false);
        });
        executor.shutdownNow();
        started = false;
        log.info("[Scheduler] stopped");
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private ScheduledFuture<?> scheduleJob(AgentSchedule schedule) {
        return switch (schedule.trigger()) {
            case Trigger.Interval i -> executor.scheduleAtFixedRate(
                    () -> fire(schedule),
                    i.every().toMillis(),
                    i.every().toMillis(),
                    TimeUnit.MILLISECONDS);

            case Trigger.Cron c -> scheduleCron(schedule, c.expression());
        };
    }

    /**
     * Minimal cron scheduler: computes delay to next fire time, then reschedules
     * itself after each execution.
     */
    private ScheduledFuture<?> scheduleCron(AgentSchedule schedule, String expression) {
        long delaySeconds = CronEvaluator.secondsUntilNext(expression);
        return executor.schedule(() -> {
            fire(schedule);
            // re-schedule for the next occurrence
            Entry current = entries.get(schedule.scheduleId());
            if (current != null && !current.paused()) {
                ScheduledFuture<?> next = scheduleCron(schedule, expression);
                entries.put(schedule.scheduleId(), new Entry(schedule, next, false));
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private AgentFuture fire(AgentSchedule schedule) {
        return registry.findById(schedule.agentId())
                .map(agent -> {
                    AgentTask task = AgentTask.of(schedule.inputTemplate());
                    AgentFuture future = AraAgents.executeAsync(agent, task, executor);
                    future.async().whenComplete((r, ex) -> {
                        if (ex != null) {
                            log.warn("[Scheduler] '{}' threw: {}", schedule.scheduleId(), ex.getMessage());
                        } else if (r.isSuccess()) {
                            log.debug("[Scheduler] '{}' completed successfully", schedule.scheduleId());
                        } else {
                            log.warn("[Scheduler] '{}' failed: {}", schedule.scheduleId(), r.failureReason());
                        }
                    });
                    return future;
                })
                .orElseGet(() -> {
                    log.warn("[Scheduler] agent {} not found for schedule '{}' — skipping",
                            schedule.agentId().value(), schedule.scheduleId());
                    return AgentFuture.completed(
                            AgentResponse.failure(
                                    "scheduler-" + schedule.scheduleId(),
                                    schedule.agentId(),
                                    "agent not found",
                                    Duration.ZERO));
                });
    }

    private Entry require(String scheduleId) {
        Entry entry = entries.get(scheduleId);
        if (entry == null) throw new NoSuchElementException(
                "No schedule registered with id: " + scheduleId);
        return entry;
    }

    private static String describetr(Trigger trigger) {
        return switch (trigger) {
            case Trigger.Interval i -> "every " + i.every();
            case Trigger.Cron c    -> "cron(" + c.expression() + ")";
        };
    }

    // ── CronEvaluator ─────────────────────────────────────────────────────────

    /**
     * Minimal 5-field cron evaluator (minute hour dom month dow).
     * Handles {@code *}, numeric values, and ranges ({@code MON-FRI}).
     * Returns seconds until the next matching instant.
     */
    static final class CronEvaluator {

        private CronEvaluator() {}

        static long secondsUntilNext(String expression) {
            String[] fields = expression.trim().split("\\s+");
            if (fields.length != 5)
                throw new IllegalArgumentException(
                        "Cron expression must have 5 fields: " + expression);

            int minute    = parseField(fields[0], 0,  59);
            int hour      = parseField(fields[1], 0,  23);
            // dom (fields[2]) and month (fields[3]) — wildcard only for now
            // dow (fields[4]) — handled via day-of-week matching
            java.util.Set<Integer> dowTargets = parseDow(fields[4]);

            LocalDateTime now  = LocalDateTime.now();
            LocalDateTime next = now.plusMinutes(1).withSecond(0).withNano(0);

            for (int i = 0; i < 10_080; i++) {  // scan up to 1 week ahead
                if ((minute < 0 || next.getMinute() == minute)
                        && (hour   < 0 || next.getHour()   == hour)
                        && (dowTargets == null || dowTargets.contains(next.getDayOfWeek().getValue() % 7))) {
                    long delay = java.time.temporal.ChronoUnit.SECONDS.between(now, next);
                    return Math.max(1, delay);
                }
                next = next.plusMinutes(1);
            }

            throw new IllegalStateException("Could not find next occurrence for cron: " + expression);
        }

        private static int parseField(String field, int min, int max) {
            if ("*".equals(field)) return -1;
            int val = Integer.parseInt(field);
            if (val < min || val > max)
                throw new IllegalArgumentException("Cron field out of range [" + min + "," + max + "]: " + field);
            return val;
        }

        /**
         * Parses a day-of-week field into the set of allowed values (0=SUN..6=SAT),
         * or {@code null} for the wildcard {@code *}. Supports single values
         * ({@code MON}, {@code 1}) and ranges ({@code MON-FRI}), wrapping around the
         * week if needed (e.g. {@code FRI-MON}).
         */
        private static java.util.Set<Integer> parseDow(String field) {
            if ("*".equals(field)) return null;
            if (field.contains("-")) {
                String[] parts = field.split("-", 2);
                int start = dowValue(parts[0]);
                int end   = dowValue(parts[1]);
                java.util.Set<Integer> set = new java.util.HashSet<>();
                int d = start;
                for (int i = 0; i < 7; i++) {
                    set.add(d);
                    if (d == end) break;
                    d = (d + 1) % 7;
                }
                return set;
            }
            return java.util.Set.of(dowValue(field));
        }

        private static int dowValue(String field) {
            return switch (field.toUpperCase()) {
                case "SUN", "0" -> 0;
                case "MON", "1" -> 1;
                case "TUE", "2" -> 2;
                case "WED", "3" -> 3;
                case "THU", "4" -> 4;
                case "FRI", "5" -> 5;
                case "SAT", "6" -> 6;
                default -> Integer.parseInt(field);
            };
        }
    }
}
