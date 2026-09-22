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
import java.util.concurrent.ExecutorService;
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
 *
 * <p><b>P2, 2026-09-22 — two executors, not one.</b> {@link #tickExecutor} is a small,
 * fixed-size pool whose only job is deciding <em>when</em> to fire (the {@code
 * scheduleAtFixedRate}/{@code schedule} calls in {@link #scheduleJob}/{@link
 * #scheduleCron}); {@link #agentExecutor} is a separate, unbounded virtual-thread-per-task
 * executor that actually runs the agent ({@link #fire}'s {@code AraAgents.executeAsync}
 * call). Before this they were the same fixed-size pool (sized {@code max(2,
 * availableProcessors())}): enough concurrent long-running agent executions filled every
 * worker, leaving nothing to fire the next tick on time — a scheduler starving its own
 * ticks on the very agents it started. Splitting them means a slow agent can never delay
 * another schedule's trigger.
 *
 * <p><b>P2, 2026-09-22 — {@code entries} mutations are atomic per id.</b> {@link #register},
 * {@link #pause} and {@link #resume} each used to read {@code entries.get(id)} and then
 * separately {@code entries.put(id, ...)} — two non-atomic map operations. Two concurrent
 * calls for the <em>same</em> id (e.g. two {@code register} calls racing on startup, or a
 * {@code resume} racing a concurrent {@code register}) could both observe the same starting
 * entry, both start their own new {@link ScheduledFuture} (via {@link #scheduleJob}), and
 * then overwrite each other's map entry — whichever {@code put} lands last wins in {@code
 * entries}, silently orphaning the other call's job: it keeps firing forever, no longer
 * reachable from {@link #list}/{@link #pause}/{@link #cancel} since the map only ever
 * tracks one {@code Entry} per id. Every mutating method below now goes through a single
 * {@code entries.compute}/{@code remove} call per id instead, which {@link ConcurrentHashMap}
 * serializes per-bin — the two racing calls above are now fully
 * ordered, and the second one always observes (and correctly cancels) the first one's job
 * before installing its own. Safe to do work inside the remapping function here specifically
 * because {@link #scheduleJob}/{@link #scheduleCron} never block (they only register a task
 * with {@link #tickExecutor} and return) — the same "short, no I/O" exception N1's own
 * findings call out, not the general "don't do slow work inside a map's per-bin lock"
 * pinning risk those findings are otherwise about.
 */
public final class LocalAgentScheduler implements AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentScheduler.class);

    private final AgentRegistry            registry;
    private final ScheduledExecutorService tickExecutor;
    private final ExecutorService          agentExecutor;

    /** Holds the AgentSchedule definition and its active ScheduledFuture — a {@code null} future means paused. */
    private record Entry(AgentSchedule schedule, ScheduledFuture<?> future) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public LocalAgentScheduler(AgentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.tickExecutor = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Thread.ofVirtual().name("ara-scheduler-tick-", 0).factory());
        this.agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ── AgentScheduler ────────────────────────────────────────────────────────

    @Override
    public void register(AgentSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule must not be null");

        entries.compute(schedule.scheduleId(), (id, existing) -> {
            if (existing != null && existing.future() != null) {
                existing.future().cancel(false);
            }
            ScheduledFuture<?> future = schedule.active() ? scheduleJob(schedule) : null;
            return new Entry(schedule, future);
        });
        log.info("[Scheduler] registered '{}' trigger={} active={}",
                schedule.scheduleId(), describe(schedule.trigger()), schedule.active());
    }

    @Override
    public void pause(String scheduleId) {
        boolean[] alreadyPaused = {false};
        entries.compute(scheduleId, (id, existing) -> {
            if (existing == null) {
                throw new NoSuchElementException("No schedule registered with id: " + scheduleId);
            }
            if (existing.future() == null) {
                alreadyPaused[0] = true;
                return existing;
            }
            existing.future().cancel(false);
            return new Entry(existing.schedule(), null);
        });
        if (!alreadyPaused[0]) {
            log.info("[Scheduler] paused '{}'", scheduleId);
        }
    }

    @Override
    public void resume(String scheduleId) {
        boolean[] alreadyActive = {false};
        entries.compute(scheduleId, (id, existing) -> {
            if (existing == null) {
                throw new NoSuchElementException("No schedule registered with id: " + scheduleId);
            }
            if (existing.future() != null) {
                alreadyActive[0] = true;
                return existing;
            }
            return new Entry(existing.schedule(), scheduleJob(existing.schedule()));
        });
        if (!alreadyActive[0]) {
            log.info("[Scheduler] resumed '{}'", scheduleId);
        }
    }

    @Override
    public void cancel(String scheduleId) {
        Entry removed = entries.remove(scheduleId);
        if (removed == null) {
            throw new NoSuchElementException("No schedule registered with id: " + scheduleId);
        }
        if (removed.future() != null) {
            removed.future().cancel(false);
        }
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
        log.info("[Scheduler] started ({} schedule(s) registered)", entries.size());
    }

    @Override
    public void stop() {
        entries.values().forEach(e -> {
            if (e.future() != null) e.future().cancel(false);
        });
        tickExecutor.shutdownNow();
        agentExecutor.shutdownNow();
        log.info("[Scheduler] stopped");
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private ScheduledFuture<?> scheduleJob(AgentSchedule schedule) {
        return switch (schedule.trigger()) {
            case Trigger.Interval i -> tickExecutor.scheduleAtFixedRate(
                    () -> safeFire(schedule),
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
        return tickExecutor.schedule(() -> {
            safeFire(schedule);
            // Re-schedule for the next occurrence — attempted regardless of whether safeFire
            // above swallowed a failure or not (P1): one bad trigger must not also prevent
            // every future one from ever being scheduled.
            try {
                Entry current = entries.get(schedule.scheduleId());
                if (current != null && current.future() != null) {
                    ScheduledFuture<?> next = scheduleCron(schedule, expression);
                    entries.put(schedule.scheduleId(), new Entry(schedule, next));
                }
            } catch (Throwable t) {
                log.error("[Scheduler] '{}' failed to reschedule after its cron trigger — "
                        + "this schedule will not fire again until re-registered", schedule.scheduleId(), t);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Runs {@link #fire}, catching and logging any {@link Throwable} instead of letting it
     * propagate.
     *
     * <p>P1, 2026-09-22: an uncaught exception thrown from a {@code scheduleAtFixedRate} task
     * silently cancels every future execution of that periodic task — a documented {@link
     * java.util.concurrent.ScheduledThreadPoolExecutor} pitfall, not a hypothetical one, since
     * {@link #fire} can throw synchronously (a malformed {@code inputTemplate}, the executor
     * rejecting a submission during shutdown) even though the agent's own execution is
     * fire-and-forget. An uncaught exception from the cron path's one-shot {@code schedule()}
     * call similarly vanishes with nothing ever observing it, which stops the reschedule step
     * below from running at all. Either way a schedule "dies" with no trace in the logs of
     * why — mirrors {@code SessionManager.sweep()}'s own catch-all for the identical
     * scheduleAtFixedRate pitfall.
     *
     * <p>Package-private (not {@code private}) so {@code LocalAgentSchedulerTest} can call it
     * directly with a schedule constructed to make {@link #fire} throw deterministically —
     * asserting the surrounding {@code scheduleAtFixedRate}/cron machinery never sees an
     * exception is otherwise only provable indirectly and non-deterministically (timing-
     * dependent), the same testability reasoning behind {@code ReactExecutionSupport}'s own
     * package-private methods.
     */
    void safeFire(AgentSchedule schedule) {
        try {
            fire(schedule);
        } catch (Throwable t) {
            log.error("[Scheduler] '{}' trigger threw unexpectedly — the schedule will keep "
                    + "firing on its own interval/cron", schedule.scheduleId(), t);
        }
    }

    private AgentFuture fire(AgentSchedule schedule) {
        return registry.findById(schedule.agentId())
                .map(agent -> {
                    AgentTask task = AgentTask.of(schedule.inputTemplate());
                    AgentFuture future = AraAgents.executeAsync(agent, task, agentExecutor);
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

    private static String describe(Trigger trigger) {
        return switch (trigger) {
            case Trigger.Interval i -> "every " + i.every();
            case Trigger.Cron c    -> "cron(" + c.expression() + ")";
        };
    }

    // ── CronEvaluator ─────────────────────────────────────────────────────────

    /**
     * Minimal 5-field cron evaluator (minute hour dom month dow).
     *
     * <p>Every field accepts:
     * <ul>
     *   <li>{@code *} — any value</li>
     *   <li>a single value ({@code 5}, or {@code MON} for day-of-week)</li>
     *   <li>a range ({@code 1-5}, {@code MON-FRI}, wrapping for day-of-week)</li>
     *   <li>a step over the whole range ({@code *}&#47;{@code 15}) or over a range
     *       ({@code 0-30}&#47;{@code 10})</li>
     *   <li>a comma-separated list combining any of the above ({@code 1,15,30},
     *       {@code MON,WED,FRI})</li>
     * </ul>
     *
     * <p>Returns seconds until the next matching instant. Day-of-month and
     * day-of-week follow the standard cron OR semantics: when both are restricted
     * (neither is {@code *}) an instant matches if it satisfies <em>either</em>
     * field. When only one is restricted, only that one is applied.
     */
    static final class CronEvaluator {

        // A leap-cycle-safe upper bound: scanning minute-by-minute over ~4 years and 1 day
        // guarantees we reach the next "Feb 29" for expressions like "0 0 29 2 *".
        private static final int MAX_SCAN_MINUTES = (366 * 4 + 1) * 24 * 60;

        private CronEvaluator() {}

        static long secondsUntilNext(String expression) {
            String[] fields = expression.trim().split("\\s+");
            if (fields.length != 5)
                throw new IllegalArgumentException(
                        "Cron expression must have 5 fields: " + expression);

            java.util.Set<Integer> minutes = parseField(fields[0], 0, 59, false);
            java.util.Set<Integer> hours   = parseField(fields[1], 0, 23, false);
            java.util.Set<Integer> doms     = parseField(fields[2], 1, 31, false);
            java.util.Set<Integer> months  = parseField(fields[3], 1, 12, false);
            java.util.Set<Integer> dows     = parseField(fields[4], 0,  6, true);

            boolean domRestricted = doms != null;
            boolean dowRestricted = dows != null;

            LocalDateTime now  = LocalDateTime.now();
            LocalDateTime next = now.plusMinutes(1).withSecond(0).withNano(0);

            for (int i = 0; i < MAX_SCAN_MINUTES; i++) {
                boolean domMatch = !domRestricted || doms.contains(next.getDayOfMonth());
                boolean dowMatch = !dowRestricted
                        || dows.contains(next.getDayOfWeek().getValue() % 7);
                // Standard cron day matching: OR when both restricted, AND (trivially) otherwise.
                boolean dayMatch = (domRestricted && dowRestricted)
                        ? (domMatch || dowMatch)
                        : (domMatch && dowMatch);

                if ((minutes == null || minutes.contains(next.getMinute()))
                        && (hours  == null || hours.contains(next.getHour()))
                        && (months == null || months.contains(next.getMonthValue()))
                        && dayMatch) {
                    long delay = java.time.temporal.ChronoUnit.SECONDS.between(now, next);
                    return Math.max(1, delay);
                }
                next = next.plusMinutes(1);
            }

            throw new IllegalStateException("Could not find next occurrence for cron: " + expression);
        }

        /**
         * Parses one cron field into the set of allowed values, or {@code null} for
         * the wildcard {@code *}. Supports comma-separated lists, ranges, and steps.
         *
         * @param dow when {@code true}, symbolic day names ({@code MON}) are accepted
         *            and {@code 7} is normalised to {@code 0} (Sunday)
         */
        private static java.util.Set<Integer> parseField(String field, int min, int max, boolean dow) {
            if ("*".equals(field)) return null;

            java.util.Set<Integer> values = new java.util.HashSet<>();
            for (String part : field.split(",")) {
                if (part.isBlank())
                    throw new IllegalArgumentException("Empty cron list element in: " + field);
                parsePart(part.trim(), min, max, dow, values);
            }
            return values;
        }

        /** Parses a single list element: a value, a range, or a step over either. */
        private static void parsePart(String part, int min, int max, boolean dow,
                                      java.util.Set<Integer> out) {
            int step = 1;
            String rangePart = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                rangePart = part.substring(0, slash);
                step = Integer.parseInt(part.substring(slash + 1));
                if (step < 1)
                    throw new IllegalArgumentException("Cron step must be >= 1: " + part);
            }

            int lo;
            int hi;
            if ("*".equals(rangePart)) {
                lo = min;
                hi = max;
            } else if (rangePart.contains("-")) {
                String[] bounds = rangePart.split("-", 2);
                lo = value(bounds[0], min, max, dow);
                hi = value(bounds[1], min, max, dow);
            } else {
                lo = value(rangePart, min, max, dow);
                hi = lo;
            }

            // day-of-week ranges may wrap around the week (e.g. FRI-MON)
            if (dow && lo > hi) {
                int span = max - min + 1;               // 7
                int length = (hi - lo + span) % span + 1; // inclusive count of days in the wrap
                for (int i = 0; i < length; i += step) {
                    out.add(min + (lo - min + i) % span);
                }
                return;
            }

            if (lo > hi)
                throw new IllegalArgumentException("Cron range start after end: " + part);
            for (int v = lo; v <= hi; v += step) out.add(v);
        }

        /** Parses a single numeric or symbolic value and range-checks it. */
        private static int value(String token, int min, int max, boolean dow) {
            int val = dow ? dowValue(token) : Integer.parseInt(token.trim());
            if (val < min || val > max)
                throw new IllegalArgumentException(
                        "Cron field out of range [" + min + "," + max + "]: " + token);
            return val;
        }

        /** Maps a day-of-week token (symbolic or numeric, {@code 7}=Sunday) to 0..6. */
        private static int dowValue(String field) {
            return switch (field.trim().toUpperCase()) {
                case "SUN", "0", "7" -> 0;
                case "MON", "1" -> 1;
                case "TUE", "2" -> 2;
                case "WED", "3" -> 3;
                case "THU", "4" -> 4;
                case "FRI", "5" -> 5;
                case "SAT", "6" -> 6;
                default -> throw new IllegalArgumentException("Invalid day-of-week: " + field);
            };
        }
    }
}
