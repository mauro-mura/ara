package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.ExecutionConfig;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.memory.MemoryManager;
import io.ara.runtime.wiring.AgentWiring;
import io.ara.runtime.wiring.Lease;
import io.ara.runtime.wiring.WiringFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages the lifecycle of {@link AgentSession}s for a single agent.
 *
 * <p>Creates sessions on-demand and keeps them in memory for the duration of the
 * session. Inactive sessions are removed by a periodic sweeper to prevent memory leaks.
 *
 * <p>Since ADR-039, each session also pins an {@link AgentWiring} built once, at
 * creation time, via {@link WiringFactory#build(AgentConfig)} — the wiring's leases
 * (LLM transports, MCP connections) are released whenever the session is removed by
 * any of the three teardown paths ({@link #invalidate}, {@link #evictStale}, {@link
 * #shutdown}), closing the leak those paths used to have (a bare {@code remove}/{@code
 * clear} with no cleanup of anything the session held).
 *
 * <p><strong>Idle TTL.</strong> A session's clock is reset on every {@link #getOrCreate}
 * hit, so the TTL measures <em>idle</em> time, not total lifetime. Elapsed time is read
 * from {@link System#nanoTime()} — a monotonic source — so an NTP correction or a manual
 * clock change can never make a live session look stale (or an abandoned one look fresh).
 * The wall-clock instant reported by {@link #listActive()} is tracked separately, for
 * display only.
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** Idle TTL used when the caller does not configure one — see {@link ExecutionConfig#DEFAULT_SESSION_TTL}. */
    static final Duration SESSION_TTL = ExecutionConfig.DEFAULT_SESSION_TTL;
    /**
     * How often {@link #evictStale()} runs. Derived from the TTL rather than fixed at
     * 5 minutes: a hardcoded period would sweep 300 times per TTL for a 1-second test
     * session, and only twice for a 10-minute one. Clamped so a very long TTL still gets
     * a reasonably prompt sweep and a very short one does not spin.
     */
    private static Duration sweepIntervalFor(Duration ttl) {
        long millis = Math.clamp(ttl.toMillis() / 6, 50L, Duration.ofMinutes(5).toMillis());
        return Duration.ofMillis(millis);
    }

    private final ConcurrentMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Function<SessionId, MemoryManager> memoryFactory;
    private final WiringFactory wiringFactory;
    private final SessionStore sessionStore;
    private final ScheduledExecutorService sweeper;
    private final long sessionTtlNanos;

    /** Uses the default idle TTL ({@link ExecutionConfig#DEFAULT_SESSION_TTL}). */
    public SessionManager(Function<SessionId, MemoryManager> memoryFactory, WiringFactory wiringFactory,
                           SessionStore sessionStore) {
        this(memoryFactory, wiringFactory, sessionStore, SESSION_TTL);
    }

    /**
     * Uses {@code sessionTtl} as the idle timeout, with a sweep period derived from it.
     * {@code AgentInstance} passes {@code AgentConfig.sessionTtl()} here.
     */
    public SessionManager(Function<SessionId, MemoryManager> memoryFactory, WiringFactory wiringFactory,
                           SessionStore sessionStore, Duration sessionTtl) {
        this(memoryFactory, wiringFactory, sessionStore, sessionTtl,
                sweepIntervalFor(Objects.requireNonNull(sessionTtl, "sessionTtl must not be null")));
    }

    /**
     * Test seam: same as above but with an explicit sweep interval, so eviction timing can
     * be pinned down independently of the TTL.
     */
    SessionManager(Function<SessionId, MemoryManager> memoryFactory, WiringFactory wiringFactory,
                    SessionStore sessionStore, Duration sessionTtl, Duration sweepInterval) {
        this.memoryFactory   = Objects.requireNonNull(memoryFactory,  "memoryFactory must not be null");
        this.wiringFactory   = Objects.requireNonNull(wiringFactory,  "wiringFactory must not be null");
        this.sessionStore    = Objects.requireNonNull(sessionStore,   "sessionStore must not be null");
        this.sessionTtlNanos = Objects.requireNonNull(sessionTtl,     "sessionTtl must not be null").toNanos();
        this.sweeper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("session-sweeper").factory());
        long periodMs = Objects.requireNonNull(sweepInterval, "sweepInterval must not be null").toMillis();
        sweeper.scheduleAtFixedRate(this::sweep, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the existing session or creates a new one, pinning its {@link AgentWiring}
     * from {@code configSnapshot}, and resets the session's idle clock. Thread-safe: single
     * creation per sessionId — {@code configSnapshot} is used only the first time a given
     * {@code sessionId} is seen; an already-live session keeps the wiring it was born with
     * regardless of what is passed on subsequent calls (ADR-039 §1: a session's pin survives
     * for its whole lifetime).
     *
     * <p><b>N1/U24, 2026-09-22:</b> that single-creation guarantee used to come from running
     * the whole build — {@code wiringFactory.build}, a real MCP connection open in
     * production — inside {@code ConcurrentHashMap.computeIfAbsent}'s own mapping function,
     * which holds the map's internal per-bin lock for as long as the function runs. A virtual
     * thread that blocks on real I/O while inside that lock pins its OS carrier on this
     * project's JDK 21 target (JEP 491, which removes this, is JDK 24) — and unlike
     * {@code DefaultResourceRegistry}'s own U23 fix, there is no per-id lock to swap for a
     * {@link ReentrantLock} here: {@code ConcurrentHashMap}'s bin lock is the JDK's own,
     * not this class's. The fix is structural instead: {@code computeIfAbsent} now only ever
     * installs a trivial, non-blocking placeholder ({@code new SessionEntry()}, no I/O), and
     * the actual build happens after it returns, under {@link SessionEntry#sessionOrBuild} —
     * a {@code ReentrantLock} <em>this class does own</em>, one per entry, so concurrent
     * callers racing to create the <em>same new</em> session still build exactly once (the
     * second caller parks on that lock instead of pinning, then reuses what the first built),
     * while sessions for different ids never contend at all.
     *
     * <p>Moving the build outside {@code computeIfAbsent} opens one narrow window that could
     * not exist before, because the whole build used to be atomic with respect to the map:
     * {@link #invalidate}/{@link #shutdown}/the sweeper can now observe a placeholder whose
     * first build has not finished yet. {@link SessionEntry#takeForClose()} and the retry
     * loop below close that window — see their own javadoc.
     *
     * @param configSnapshot the agent's current config, read exactly once by the caller
     *                       before this call — never re-read here, so a session born
     *                       mid-reconfigure gets an entirely-old or entirely-new config,
     *                       never a torn hybrid
     */
    public AgentSession getOrCreate(SessionId sessionId, AgentConfig configSnapshot) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(configSnapshot, "configSnapshot must not be null");
        while (true) {
            SessionEntry entry = sessions.computeIfAbsent(sessionId.value(), id -> new SessionEntry());
            AgentSession session = entry.sessionOrBuild(
                    () -> newSession(sessionId, configSnapshot),
                    built -> built.wiring().close());
            // P5/U16, 2026-09-23: re-verify `entry` is still the one actually in the map
            // before trusting `session`. Without this, the already-built fast path above
            // (session != null, returned on `entry`'s first volatile read, before ever
            // touching buildLock) could hand back a session whose wiring a concurrent
            // evictStale/invalidate/shutdown had *already* closed moments earlier: that
            // teardown path only ever clears `entry` from this map and marks it via
            // takeForClose() — it never nulls out `entry.session` itself — so a caller
            // already holding a reference to this same `entry` (from computeIfAbsent
            // above, in the same call) would otherwise never notice.
            if (session != null && sessions.get(sessionId.value()) == entry) {
                // Mutates the entry in place. A record + `entry.touch()` would build a
                // replacement that nothing ever stores, freezing lastAccessed at creation
                // time and making the sweeper reclaim busy sessions on a fixed lifetime.
                entry.touch();
                return session;
            }
            // Either the build was torn down while still in flight (session == null —
            // SessionEntry#sessionOrBuild already closed what it built), or this entry was
            // concurrently removed after we fetched it (the re-verify above). Drop the now-
            // stale placeholder if it is still the one sitting in the map (a third caller
            // may already have replaced it after computeIfAbsent raced the teardown too)
            // and retry as if this sessionId had never been seen, exactly the outcome a
            // caller arriving one instant later would have gotten anyway.
            sessions.remove(sessionId.value(), entry);
        }
    }

    private AgentSession newSession(SessionId sessionId, AgentConfig configSnapshot) {
        AgentWiring wiring = wiringFactory.build(configSnapshot);
        AgentSession session = new AgentSession(sessionId, memoryFactory.apply(sessionId), wiring, sessionStore);
        // The registry stays session-agnostic (ADR-039 layering guardrail): it only ever
        // calls Lease.evict(); binding what eviction MEANS for this session (cooperative
        // cancellation) is this class's job, done once here, right after the wiring is built.
        for (Lease<?> lease : wiring.leases()) {
            lease.bindEvictionTarget(session::requestCancel);
        }
        return session;
    }

    /**
     * Returns a snapshot of all currently live sessions. Excludes an entry whose first build
     * (U24) is still in flight — indistinguishable, to a caller of this method, from a
     * session that simply has not been created yet.
     */
    public Collection<AgentSession> activeSessions() {
        return sessions.values().stream()
                .map(SessionEntry::session)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Returns how many sessions are currently live — O(1), and without the per-session
     * {@code List} that {@link #activeSessions()} allocates for a caller that only wants
     * the count.
     *
     * <p><b>U24 note:</b> unlike {@link #activeSessions()}, this counts an entry whose first
     * build is still in flight too — keeping this O(1) (a placeholder is real map occupancy
     * the moment {@code computeIfAbsent} installs it) costs a momentary, build-duration-only
     * over-count relative to {@code activeSessions().size()} rather than the O(n) scan
     * filtering it out would cost on every call.
     */
    public int activeSessionCount() {
        return sessions.size();
    }

    /** Explicitly removes a session (e.g. at the end of a conversation), releasing its wiring's leases. */
    public void invalidate(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        removeAndClose(sessionId.value());
    }

    /**
     * Read-only lookup: returns the session if one currently exists for {@code sessionId},
     * without creating it — unlike {@link #getOrCreate}. Use this to inspect a session's
     * state (e.g. its conversation history) without the side effect of spawning an empty
     * one for an id that was never used or has already been evicted by the TTL sweeper.
     *
     * <p>Being read-only, this does <em>not</em> reset the idle clock: inspecting a session
     * from an admin endpoint must never keep it alive past its TTL.
     *
     * <p>U24: also empty while the entry's first build is still in flight — this method
     * never blocks waiting for one, matching its own "no side effect" contract above.
     */
    public Optional<AgentSession> find(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        SessionEntry entry = sessions.get(sessionId.value());
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.session());
    }

    /**
     * Returns a snapshot of all active sessions with metadata for observability.
     * Each entry is an immutable map containing: session_id, turn_count,
     * last_accessed_at (epoch millis), ttl_remaining_ms, state.
     */
    public List<Map<String, Object>> listActive() {
        long now = System.nanoTime();
        return sessions.entrySet().stream()
                .filter(e -> e.getValue().session() != null)   // U24: first build still in flight
                .<Map<String, Object>>map(e -> {
                    SessionEntry entry = e.getValue();
                    long remainingNanos = sessionTtlNanos - (now - entry.lastAccessedNanos());
                    return Map.of(
                            "session_id",       e.getKey(),
                            "turn_count",       entry.session().conversationHistory().size(),
                            "last_accessed_at", entry.lastAccessedEpochMillis(),
                            "ttl_remaining_ms", Math.max(0L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
                            "state",            entry.session().stateMachine().current().name());
                })
                .toList();
    }

    /**
     * Shuts down the sweeper and releases every live session's wiring.
     *
     * <p><b>P5/U16, 2026-09-23:</b> a session created via {@link #getOrCreate} concurrently
     * with — and strictly after — the snapshot below is not in it, so the drain loop never
     * closes its wiring: a real, accepted residual leak. This is narrow in practice because
     * {@code AgentInstance} re-checks its own {@code closed} flag immediately after {@code
     * getOrCreate} returns and tears down (rather than uses) any session created during
     * exactly this race — the caller {@code shutdown()} exists to serve — but {@code
     * SessionManager} has no such flag of its own to enforce that for every possible caller.
     *
     * <p><b>Correction, found by stress-testing this same fix, 2026-09-23:</b> the original
     * version of this method added a final {@code sessions.clear()} here, reasoned as a
     * harmless safety net ("cannot close the orphan's wiring, but at least drops the
     * dangling reference instead of leaking it in the map forever"). That reasoning missed
     * a worse interaction: a {@code clear()} racing the exact scenario above — a caller
     * concurrently publishing a new session and then, per its own guard, immediately
     * invalidating it again (the very sequence {@code AgentInstance.execute()} runs) — can
     * remove that entry out from under the caller's own {@link #invalidate}/{@code
     * removeAndClose} call an instant later, which then finds nothing and closes nothing.
     * Observed under stress: builds a session, closes zero — a leak {@code clear()}
     * <em>caused</em> rather than merely failed to prevent, strictly worse than the
     * "dangling but still-referenced, still-closeable-by-a-future-shutdown-call" state
     * before it. Removed. What remains is exactly the single drain pass this method had
     * before U16 — the residual leak in this method's own first paragraph is real and
     * accepted, same as it always was, but nothing here worsens it anymore.
     */
    public void shutdown() {
        sweeper.shutdownNow();
        for (String key : List.copyOf(sessions.keySet())) {
            removeAndClose(key);
        }
    }

    /**
     * Sweeper entry point. {@link ScheduledExecutorService#scheduleAtFixedRate} silently
     * cancels all future runs if the task throws, so a single unexpected failure here would
     * disable session reclamation for the lifetime of the JVM — hence the catch-all.
     */
    private void sweep() {
        try {
            evictStale();
        } catch (Throwable t) {
            log.warn("Session sweeper failed; sessions will be retried on the next run", t);
        }
    }

    /**
     * Removes sessions idle longer than {@link #SESSION_TTL}, releasing each one's wiring.
     * Sessions with a task in flight are skipped: closing their wiring would pull the LLM
     * transport out from under a running execution.
     *
     * <p><b>P5/U15, 2026-09-23:</b> the {@code !session.isBusy()} check used to be a plain
     * snapshot, taken here and acted on later in {@link #removeAndClose} — a task that
     * acquired {@link AgentSession#executionLock()} in between found its wiring closed out
     * from under it mid-execution. {@link #evictIfStillIdle} closes that window: it holds
     * the session's own {@code executionLock} (via {@code tryLock()}, so a genuinely busy
     * session is skipped rather than waited on) across the removal itself, so no task can
     * begin running on a session between this method deciding it is idle and it actually
     * being torn down.
     *
     * <p><b>P5/U16, 2026-09-23:</b> each eviction now runs on its own dedicated virtual
     * thread instead of inline on this method's caller — the single {@link #sweeper}
     * thread. {@code wiring().close()} can do real teardown I/O (closing an MCP
     * connection); before this, a single slow or hanging close blocked eviction of every
     * <em>other</em> stale session in the same pass, and — worse — blocked {@code
     * scheduleAtFixedRate}'s next scheduled tick, since that mechanism runs the same task
     * serially on one thread: one stuck close would have silently disabled session
     * reclamation for the rest of the JVM's lifetime, not just delayed it. Evictions
     * across different sessions are independent of each other by construction (each only
     * ever touches its own {@link SessionEntry}/{@link AgentSession#executionLock()}), so
     * running them concurrently introduces no new coordination need.
     */
    private void evictStale() {
        long now = System.nanoTime();
        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, SessionEntry> e : sessions.entrySet()) {
            SessionEntry entry = e.getValue();
            AgentSession session = entry.session();
            if (session == null) continue;   // U24: first build still in flight — never stale
            if (now - entry.lastAccessedNanos() >= sessionTtlNanos) {
                staleKeys.add(e.getKey());
            }
        }
        for (String key : staleKeys) {
            Thread.ofVirtual().start(() -> evictIfStillIdle(key));
        }
    }

    /**
     * U15: the atomic "still idle, and not busy, and still this session" check-and-evict
     * behind {@link #evictStale}. Re-reads the entry (rather than trusting the snapshot
     * {@link #evictStale} built) because a {@code getOrCreate} touch, or an unrelated
     * teardown, may have happened since; re-checks the TTL for the same reason — a session
     * touched between the snapshot and this call is no longer stale and must survive.
     */
    private void evictIfStillIdle(String key) {
        SessionEntry entry = sessions.get(key);
        if (entry == null) return;
        AgentSession session = entry.session();
        if (session == null) return;   // first build still in flight, or torn down already
        if (System.nanoTime() - entry.lastAccessedNanos() < sessionTtlNanos) return;   // touched since the snapshot

        ReentrantLock executionLock = session.executionLock();
        if (!executionLock.tryLock()) return;   // busy right now — retried on the next sweep
        try {
            // Identity-checked remove: `entry` may already have been replaced (e.g. an
            // invalidate() racing this same sweep) by the time we get here, and removing
            // by bare key would then tear down whatever session currently sits at `key`
            // instead of a no-op.
            if (sessions.remove(key, entry)) {
                entry.takeForClose().ifPresent(s -> s.wiring().close());
            }
        } finally {
            executionLock.unlock();
        }
    }

    /**
     * Removes {@code key} and releases the removed session's wiring. The map removal is the
     * single point of ownership transfer: only the caller that observes a non-null return
     * from {@code remove} closes the wiring, so two concurrent teardown paths
     * ({@code invalidate} racing the sweeper) can never double-close.
     *
     * <p>U24: {@link SessionEntry#takeForClose()} extends that same single-owner rule to a
     * third case — an entry whose first build has not finished yet. It closes the built
     * session immediately if there is one, or marks the entry so the in-flight
     * {@link SessionEntry#sessionOrBuild} closes what <em>it</em> builds instead of
     * publishing it (see {@link #getOrCreate}). Either way exactly one side closes the
     * wiring, never both, never neither.
     */
    private void removeAndClose(String key) {
        SessionEntry removed = sessions.remove(key);
        if (removed == null) return;
        removed.takeForClose().ifPresent(session -> session.wiring().close());
    }

    /**
     * Mutable holder for one session slot. Deliberately a class and not a record: {@link
     * #touch()} must update the live entry the map already holds, and the timestamp fields
     * are {@code volatile} so the sweeper thread always observes the latest write without
     * any locking on the {@code getOrCreate} hot path.
     *
     * <p><b>U24.</b> An instance starts empty ({@code session == null}) — {@code
     * computeIfAbsent} in {@link #getOrCreate} installs it before any build has happened, so
     * that installation is trivial and non-blocking. {@link #sessionOrBuild} then does
     * exactly one of: return an already-built session (the common case, one {@code volatile}
     * read); or become the single builder for a brand-new one, under {@link #buildLock} — a
     * {@link ReentrantLock}, not a monitor, so the real I/O {@code wiringFactory.build} does
     * inside it parks a blocked virtual thread instead of pinning its carrier (N1). Every
     * other caller racing for the *same* new session parks on that same lock and reuses what
     * the first one built, rather than each building its own (which is what made the old,
     * whole-build-inside-{@code computeIfAbsent} version single-creation in the first place —
     * see {@link #getOrCreate}'s own javadoc for why that guarantee had to move here).
     */
    private static final class SessionEntry {
        private final ReentrantLock buildLock = new ReentrantLock();
        /** {@code null} until built; read on the hot path without acquiring {@link #buildLock}. */
        private volatile AgentSession session;
        /**
         * Set by {@link #takeForClose()} <em>before</em> it attempts {@link #buildLock} —
         * deliberately a plain {@code volatile}, not a field only ever touched under the lock.
         * A build in flight holds {@link #buildLock} for the whole slow call (real I/O), so a
         * racing {@link #takeForClose()} cannot acquire that lock — and therefore cannot make a
         * lock-guarded write visible to {@link #sessionOrBuild}'s post-build check — until
         * {@code sessionOrBuild} itself is done and releases it, which is always too late for
         * that check to see. Writing the flag first, outside the lock, is what makes it visible
         * to the post-build check the moment it happens rather than only after the build
         * finishes; {@link #buildLock} is then used only to serialize the two sides' access to
         * {@link #session} itself (who gets to read/write it), never to order this flag.
         */
        private volatile boolean tornDownBeforeBuild;
        /** Monotonic, authoritative for TTL arithmetic. */
        private volatile long lastAccessedNanos;
        /** Wall clock, for {@link #listActive()} display only — never used to measure elapsed time. */
        private volatile long lastAccessedEpochMillis;

        /**
         * Returns the already-built session, or builds one via {@code builder} — exactly
         * once for this entry, however many callers race here concurrently; the rest park on
         * {@link #buildLock} and then reuse what the winner built, never building a second
         * one of their own.
         *
         * <p>Returns {@code null} only when {@link #takeForClose()} tore this entry down
         * (invalidated, shut down, or swept) — either before this <em>first</em> build ever
         * started, or at any point <em>while</em> it was still in flight (both are races the
         * old code could not have, because the whole build used to run inside {@code
         * ConcurrentHashMap.computeIfAbsent}'s own atomic section). When that happens, {@code
         * closeTornDown} is handed the session this call just finished building (so its wiring
         * gets closed — the entry is already gone from {@link #sessions}, so nothing else ever
         * would) and this returns {@code null} for {@link #getOrCreate} to retry with a fresh
         * entry, exactly as if this one had never existed.
         */
        AgentSession sessionOrBuild(Supplier<AgentSession> builder, Consumer<AgentSession> closeTornDown) {
            AgentSession existing = session;
            if (existing != null) return existing;
            buildLock.lock();
            try {
                if (session != null) return session;
                if (tornDownBeforeBuild) return null;   // torn down before we ever started building
                AgentSession built = builder.get();
                if (tornDownBeforeBuild) {              // torn down WHILE builder.get() was running —
                    closeTornDown.accept(built);        // visible here precisely because the flag write
                    return null;                        // in takeForClose() does not wait for buildLock
                }
                session = built;
                return session;
            } finally {
                buildLock.unlock();
            }
        }

        /**
         * Called after this entry has been removed from {@link #sessions}, to hand back the
         * session to close — or, if the first build has not finished yet, to mark this entry
         * so {@link #sessionOrBuild} closes what it builds instead of publishing it. The flag
         * write happens first and needs no lock (see its own javadoc for why that is what makes
         * a torn-down-while-building race actually observable); {@link #buildLock} is acquired
         * only afterward, purely to read {@link #session} without racing {@code
         * sessionOrBuild}'s own write of it — so exactly one side ever ends up closing the
         * wiring, never both, never neither.
         */
        Optional<AgentSession> takeForClose() {
            tornDownBeforeBuild = true;
            buildLock.lock();
            try {
                return Optional.ofNullable(session);
            } finally {
                buildLock.unlock();
            }
        }

        void touch() {
            this.lastAccessedNanos       = System.nanoTime();
            this.lastAccessedEpochMillis = System.currentTimeMillis();
        }

        /** {@code null} while this entry's first build is still in flight. */
        AgentSession session()          { return session; }
        long lastAccessedNanos()        { return lastAccessedNanos; }
        long lastAccessedEpochMillis()  { return lastAccessedEpochMillis; }
    }
}
