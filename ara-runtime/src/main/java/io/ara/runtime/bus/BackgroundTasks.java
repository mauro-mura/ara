package io.ara.runtime.bus;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.RunContext;
import io.ara.core.bus.Delegation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * The sub-tasks one run has started in the background and not yet collected.
 *
 * <h2>Why per run</h2>
 * A task belongs to the run that started it. The registry is created by the reasoning loop
 * when the run begins, travels to the tools in the task's {@link RunContext}, and is emptied
 * when the run ends — so nothing a run started can outlive it, and a task id means nothing to
 * any other run. A delegate gets a registry of its own (the key is dropped at the delegation
 * hop, see {@link #without}), so it can neither see nor wait for its siblings or its parent.
 * Work that must outlive a run is not a sub-task of that run: start it from code
 * ({@code AraRuntime.submit}) or from the scheduler.
 *
 * <h2>How results get back</h2>
 * Pushed, not polled. The loop calls {@link #deliverFinished} before every model call and
 * puts each finished task's result into the working memory as a message, so the model never
 * has to ask whether something finished — a question that costs a whole model turn, over the
 * whole conversation, every time it is asked. {@link #await} exists for the one case where the
 * model has nothing else to do until a task ends. A task is handed over once: delivering or
 * awaiting it removes it.
 *
 * <h2>Alternatives not taken</h2>
 * A registry shared by the whole runtime (like the gateway's {@code RunStore}) would need a
 * lifetime of its own, with expiry, because its entries must survive the request that made
 * them; this one must do the opposite and never outlive its run, and one object with both
 * rules is harder to get right than two with one each. {@code StructuredTaskScope} would give
 * the same "no child outlives its parent" rule, but it is still a preview API.
 *
 * <p>Thread-safe: parallel tool calls of one turn start and read tasks concurrently with the
 * loop thread. One lock guards the bookkeeping and is never held across anything that can
 * block (starting a delegate can wait on a human approval, so the slot is reserved under the
 * lock and the start happens outside it).
 */
public final class BackgroundTasks {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTasks.class);

    /** Key under which the registry travels in a task's {@link RunContext}. */
    public static final String KEY = "ara.backgroundTasks";

    /**
     * Opens every message the loop injects. The result of a delegate is data that a model
     * produced, not something the user said; saying so keeps a delegate that writes "ignore
     * your instructions" from sounding like the person the agent works for.
     */
    static final String AUTOMATED = "[automated — background task result, not a message from the user]";

    private static final int MAX_AWAIT_SECONDS = 3_600;

    private final AsyncDelegation limits;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Entry> entries = new LinkedHashMap<>();   // guarded by lock; start order
    private int reserved;                                                // guarded by lock
    private Set<String> openAtTurnStart = Set.of();                      // guarded by lock

    /** A started task. Immutable but for {@code cancelled}, which only ever goes from false to true. */
    private static final class Entry {
        final String id;
        final String label;
        final String agentId;
        final Delegation delegation;
        volatile boolean cancelled;

        Entry(String id, String label, String agentId, Delegation delegation) {
            this.id = id;
            this.label = label;
            this.agentId = agentId;
            this.delegation = delegation;
        }
    }

    public BackgroundTasks(AsyncDelegation limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits must not be null");
    }

    // ── travelling in the RunContext ──────────────────────────────────────────

    /** The registry of the run {@code context} belongs to, if that run may start background tasks. */
    public static Optional<BackgroundTasks> from(RunContext context) {
        return Optional.ofNullable(context.opaque(KEY, BackgroundTasks.class));
    }

    /** {@code task} with this registry in its context. */
    public AgentTask attachTo(AgentTask task) {
        return task.withRunContext(task.runContext().withOpaque(KEY, this));
    }

    /** {@code context} for a delegate: without the caller's registry, which is the caller's alone. */
    static RunContext without(RunContext context) {
        return context.withOpaque(KEY, null);
    }

    // ── starting ──────────────────────────────────────────────────────────────

    /**
     * Takes a slot for a task about to be started.
     *
     * @return {@code null} when a slot was taken — the caller must then {@link #register} or
     *         {@link #unreserve} it — otherwise the reason to refuse with
     */
    public String reserve() {
        lock.lock();
        try {
            if (running() + reserved >= limits.maxConcurrent()) {
                return "Too many background tasks are running (limit " + limits.maxConcurrent() + "): "
                        + describeRunning() + ". Collect them with await_tasks, or stop one with cancel_task.";
            }
            reserved++;
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Gives back a slot taken by {@link #reserve} for a task that did not start. */
    public void unreserve() {
        lock.lock();
        try {
            reserved--;
        } finally {
            lock.unlock();
        }
    }

    /** Records a started delegation in the slot {@link #reserve} took. */
    public void register(String label, String agentId, Delegation delegation) {
        lock.lock();
        try {
            reserved--;
            entries.put(delegation.taskId(), new Entry(delegation.taskId(), label, agentId, delegation));
        } finally {
            lock.unlock();
        }
    }

    // ── what the loop does ────────────────────────────────────────────────────

    /**
     * Takes out every task that has finished since the last call, as the messages to hand the
     * model. Called before each model call, so it also answers "did anything finish while the
     * model was thinking".
     */
    public List<String> deliverFinished() {
        lock.lock();
        try {
            List<String> messages = new ArrayList<>();
            entries.values().removeIf(entry -> {
                if (!isTerminal(entry)) {
                    return false;
                }
                messages.add(AUTOMATED + "\n" + render(entry));
                return true;
            });
            return messages;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the start of a model turn: an {@link #await} with no ids means the tasks open
     * <em>now</em>. The calls of one turn run in parallel, so without this a task started by
     * one of them could or could not be seen by another, depending on the threads.
     */
    public void beginTurn() {
        lock.lock();
        try {
            openAtTurnStart = Set.copyOf(entries.keySet());
        } finally {
            lock.unlock();
        }
    }

    /** {@code true} while any task has not been handed over — running, or finished and uncollected. */
    public boolean hasTasks() {
        lock.lock();
        try {
            return !entries.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for every running task until {@code deadline}, then takes out what finished, as
     * {@link #deliverFinished} does. Used when the model gives its final answer with tasks
     * still open: the run waits for its children instead of dropping them.
     */
    public List<String> awaitAllAndDeliver(Instant deadline) {
        waitFor(runningFutures(), Duration.between(Instant.now(), deadline));
        return deliverFinished();
    }

    /**
     * Stops every running task and forgets all of them, finished or not. The end of a run: what
     * a run started does not outlive it.
     *
     * @return a description of each task that was still running and was stopped
     */
    public List<String> cancelAll() {
        List<Entry> running = new ArrayList<>();
        lock.lock();
        try {
            for (Entry entry : entries.values()) {
                if (isTerminal(entry)) {
                    log.debug("Background task [{}] finished but its result was never collected", entry.id);
                } else {
                    entry.cancelled = true;
                    running.add(entry);
                }
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
        // Outside the lock: stopping a delegate terminates its session, which is not ours to wait on.
        running.forEach(entry -> entry.delegation.cancel().run());
        return running.stream().map(BackgroundTasks::describe).toList();
    }

    // ── what the tools do ─────────────────────────────────────────────────────

    /**
     * Waits for the given tasks — or, with none given, those open at the start of this turn —
     * to finish, at most {@code timeout}, and reports each: its result if it finished (and
     * hands it over), otherwise that it is still working. With a zero timeout it only lists.
     */
    public String await(List<String> taskIds, Duration timeout) {
        Duration bounded = timeout.compareTo(Duration.ofSeconds(MAX_AWAIT_SECONDS)) > 0
                ? Duration.ofSeconds(MAX_AWAIT_SECONDS) : timeout;
        List<String> wanted = wanted(taskIds);
        waitFor(futuresOf(wanted), bounded);
        lock.lock();
        try {
            if (wanted.isEmpty()) {
                return "No background tasks are open.";
            }
            return wanted.stream().map(this::reportAndHandOver).collect(Collectors.joining("\n\n"));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops a running task and hands it over as cancelled. A task that already finished is
     * handed over with its result instead: there is nothing left to stop, and the result is
     * the part worth having.
     */
    public String cancel(String taskId) {
        Entry entry;
        boolean stop;
        lock.lock();
        try {
            entry = entries.remove(taskId);
            if (entry == null) {
                return unknown(taskId);
            }
            stop = !isTerminal(entry);
            entry.cancelled |= stop;
        } finally {
            lock.unlock();
        }
        if (stop) {
            entry.delegation.cancel().run();   // outside the lock, as in cancelAll
        }
        return render(entry);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Ids to wait on: the ones named, or those open at the start of the turn that are still here. */
    private List<String> wanted(List<String> taskIds) {
        lock.lock();
        try {
            if (taskIds != null && !taskIds.isEmpty()) {
                return List.copyOf(taskIds);
            }
            return entries.keySet().stream().filter(openAtTurnStart::contains).toList();
        } finally {
            lock.unlock();
        }
    }

    private String reportAndHandOver(String taskId) {
        Entry entry = entries.get(taskId);
        if (entry == null) {
            return unknown(taskId);
        }
        if (!isTerminal(entry)) {
            return "task " + describe(entry) + ": still working";
        }
        entries.remove(taskId);
        return render(entry);
    }

    private static String unknown(String taskId) {
        return "unknown task " + taskId + " (never started in this run, or its result was already delivered)";
    }

    private List<CompletableFuture<AgentResponse>> runningFutures() {
        lock.lock();
        try {
            return entries.values().stream().filter(entry -> !isTerminal(entry))
                    .map(entry -> entry.delegation.response()).toList();
        } finally {
            lock.unlock();
        }
    }

    private List<CompletableFuture<AgentResponse>> futuresOf(List<String> taskIds) {
        lock.lock();
        try {
            return taskIds.stream().map(entries::get).filter(java.util.Objects::nonNull)
                    .map(entry -> entry.delegation.response()).toList();
        } finally {
            lock.unlock();
        }
    }

    /** Every wait is bounded; an interrupt ends it early and is passed on. */
    private static void waitFor(List<CompletableFuture<AgentResponse>> futures, Duration timeout) {
        if (futures.isEmpty() || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Not an error: the caller reports whatever is still working.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
            // A delegate that threw or was cancelled is a terminal state, read per task below.
        }
    }

    private int running() {
        return (int) entries.values().stream().filter(entry -> !isTerminal(entry)).count();
    }

    private String describeRunning() {
        return entries.values().stream().filter(entry -> !isTerminal(entry))
                .map(BackgroundTasks::describe).collect(Collectors.joining(", "));
    }

    private static boolean isTerminal(Entry entry) {
        return entry.cancelled || entry.delegation.response().state() != Future.State.RUNNING;
    }

    private static String describe(Entry entry) {
        return entry.id + " \"" + entry.label + "\" (" + entry.agentId + ")";
    }

    /** One finished task as text for a model: its state, and its result or why it has none. */
    private String render(Entry entry) {
        String head = "task " + describe(entry) + ": ";
        if (entry.cancelled) {
            return head + "cancelled";
        }
        CompletableFuture<AgentResponse> future = entry.delegation.response();
        return switch (future.state()) {
            case SUCCESS -> {
                AgentResponse response = future.resultNow();
                yield response.isSuccess()
                        ? head + "completed\n[" + entry.agentId + "] " + limited(response.content())
                        : head + "failed — " + LocalMessageBus.describeNoAnswer(response);
            }
            case FAILED -> head + "failed — " + future.exceptionNow().getMessage();
            case CANCELLED -> head + "cancelled";
            case RUNNING -> head + "still working";
        };
    }

    private String limited(String content) {
        int max = limits.maxResultChars();
        return content.length() <= max ? content
                : content.substring(0, max) + "…[+" + (content.length() - max) + " characters not shown]";
    }
}
