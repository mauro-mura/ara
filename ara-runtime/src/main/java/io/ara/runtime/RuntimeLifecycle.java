package io.ara.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Encapsulates the runtime lifecycle state machine and the shared virtual-thread
 * executor (ADR-0069). Separates lifecycle management from the agent-creation
 * and scheduling concerns in {@link AraRuntime}.
 *
 * <p>Phases: {@code NEW → STARTED ⇄ STOPPED}. Explicit restart via
 * {@link #start()} is supported (a fresh executor is provisioned); only the
 * implicit auto-start performed by {@link AraRuntime#createAgent} /
 * {@link AraRuntime#submit} is limited to the {@code NEW} phase — see
 * {@link #autoStart()}.
 *
 * <p><b>{@link #lock} is a {@link ReentrantLock}, not a monitor</b> ({@code
 * docs/analysis/concurrency-hardening.md} N1/U25, 2026-09-22): {@link #stop()} blocks on
 * {@code awaitTermination} (up to {@code shutdownTimeoutSec} seconds) while holding it, and
 * {@link AraRuntime#stop}/{@link AraRuntime#destroyAgent} block on real MCP-connection close
 * (via {@code AgentFactory.destroyPermanently}) while holding the very same lock (see {@link
 * #getLock()}) — on this project's JDK 21 target (JEP 491, which removes this, is JDK 24) a
 * virtual thread that blocks while holding a {@code synchronized} monitor pins its OS carrier
 * for the whole block. This lock is shared by every lifecycle operation
 * ({@link #autoStart}, {@link #getAgentExecutorOrAutoStart}, and every {@code
 * synchronized (lifecycle.getLock())} in {@code AraRuntime}) — with a monitor, pinning here
 * would have starved unrelated virtual threads scheduler-wide (the carrier pool is shared and
 * small, {@code Runtime.availableProcessors()} by default), not just other callers of this one
 * lock. A {@code ReentrantLock} held across the same blocking calls parks instead: same
 * critical sections, same mutual exclusion (deliberately <em>not</em> reduced to a snapshot —
 * see this class's own methods' javadoc for why), only the primitive changed — exactly U23's
 * precedent for {@code DefaultResourceRegistry.Entry.lock}.
 */
final class RuntimeLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RuntimeLifecycle.class);

    enum Phase { NEW, STARTED, STOPPED }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile Phase phase = Phase.NEW;
    private volatile Executor agentExecutor;
    private final QuiescenceTracker quiescenceTracker;
    private final String name;
    private final int shutdownTimeoutSec;

    RuntimeLifecycle(String name, int shutdownTimeoutSec) {
        this.name = name;
        this.shutdownTimeoutSec = shutdownTimeoutSec;
        this.quiescenceTracker = new QuiescenceTracker();
    }

    /**
     * Transitions the phase from {@code NEW} or {@code STOPPED} to {@code STARTED},
     * provisioning a fresh virtual-thread executor. No-op if already {@code STARTED}.
     */
    void start() {
        lock.lock();
        try {
            if (phase == Phase.STARTED) return;
            agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
            phase = Phase.STARTED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Transitions the phase to {@code STOPPED} and shuts down the executor
     * gracefully. No-op if already {@code STOPPED}.
     *
     * <p>Deliberately still holds {@link #lock} across {@link #shutdownExecutor()}'s
     * {@code awaitTermination} wait, rather than snapshotting the executor and draining it
     * after releasing the lock — see this class's own javadoc for the U25 rationale: a
     * {@code ReentrantLock} held here parks other lifecycle callers instead of pinning their
     * carriers, which is what N1 actually requires fixed. Releasing the lock earlier would
     * let a concurrent {@link #start()} (e.g. a supervisor racing a restart) begin
     * provisioning a fresh executor and re-running {@code AraRuntime}'s {@code AgentProvider}
     * loop — which can reuse the very same agent ids — before this call has finished tearing
     * the old ones down, a race this class's original, fully-serialized {@code stop()} never
     * had. Kept out of scope for U25, which is about pinning, not about shrinking this
     * lock's contention window.
     */
    void stop() {
        lock.lock();
        try {
            if (phase != Phase.STARTED) return;
            shutdownExecutor();
            phase = Phase.STOPPED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Prepares the runtime for agent creation or task submission. No-op if
     * already {@code STARTED}; throws {@link IllegalStateException} if
     * {@code STOPPED} — a stopped runtime cannot be implicitly resurrected.
     */
    void autoStart() {
        if (phase == Phase.STOPPED) {
            throw new IllegalStateException("AraRuntime [" + name + "] has been stopped — call start()"
                    + " to restart it explicitly before creating agents or submitting tasks");
        }
        start(); // no-op when already STARTED
    }

    /** Returns the shared virtual-thread executor, or {@code null} before {@link #start()}. */
    Executor getAgentExecutor() { return agentExecutor; }

    /**
     * Returns the executor, auto-starting the runtime first if it was never
     * started ({@code NEW}) and throwing {@link IllegalStateException} if it was
     * stopped — the atomic slow path behind {@link AraRuntime#submit}.
     */
    Executor getAgentExecutorOrAutoStart() {
        lock.lock();
        try {
            autoStart(); // throws when stopped; no-op when STARTED
            return agentExecutor;
        } finally {
            lock.unlock();
        }
    }

    /** {@code true} between a {@link #start()} and the next {@link #stop()}. */
    boolean isStarted() { return phase == Phase.STARTED; }

    /** {@code true} after a {@link #stop()}. */
    boolean isStopped() { return phase == Phase.STOPPED; }

    /**
     * The {@link ReentrantLock} that serializes lifecycle transitions and agent operations —
     * see this class's own javadoc for why it is a {@code ReentrantLock} and not a monitor
     * (U25). {@code AraRuntime} calls {@code lock()}/{@code unlock()} on the returned instance
     * directly (in a {@code try}/{@code finally}) rather than {@code synchronized} on it.
     */
    ReentrantLock getLock() { return lock; }

    /** Current phase, for diagnostics and health-check surfaces. */
    Phase phase() { return phase; }

    // ── quiescence tracking — in-flight task bookkeeping ────────────────────

    void taskStarted() { quiescenceTracker.taskStarted(); }

    void taskFinished() { quiescenceTracker.taskFinished(); }

    boolean awaitQuiescence(long timeout, TimeUnit unit) throws InterruptedException {
        return quiescenceTracker.awaitQuiescence(timeout, unit);
    }

    int inFlightCount() { return quiescenceTracker.inFlightCount(); }

    /**
     * Shuts the shared executor down gracefully, waiting up to
     * {@code shutdownTimeoutSec} seconds for in-flight tasks to finish
     * before forcing {@code shutdownNow()}.
     */
    private void shutdownExecutor() {
        if (!(agentExecutor instanceof ExecutorService es)) return;
        es.shutdown();
        try {
            if (!es.awaitTermination(shutdownTimeoutSec, TimeUnit.SECONDS)) {
                log.warn("AraRuntime [{}] executor did not drain within {}s — forcing shutdownNow",
                        name, shutdownTimeoutSec);
                es.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            es.shutdownNow();
        }
    }
}
