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
import java.util.function.Function;

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
     * from {@code configSnapshot}, and resets the session's idle clock. Thread-safe:
     * {@code computeIfAbsent} guarantees single creation per sessionId — {@code
     * configSnapshot} is used only the first time a given {@code sessionId} is seen; an
     * already-live session keeps the wiring it was born with regardless of what is passed
     * on subsequent calls (ADR-039 §1: a session's pin survives for its whole lifetime).
     *
     * @param configSnapshot the agent's current config, read exactly once by the caller
     *                       before this call — never re-read here, so a session born
     *                       mid-reconfigure gets an entirely-old or entirely-new config,
     *                       never a torn hybrid
     */
    public AgentSession getOrCreate(SessionId sessionId, AgentConfig configSnapshot) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(configSnapshot, "configSnapshot must not be null");
        SessionEntry entry = sessions.computeIfAbsent(
                sessionId.value(),
                id -> new SessionEntry(newSession(sessionId, configSnapshot)));
        // Mutates the entry in place. A record + `entry.touch()` would build a replacement
        // that nothing ever stores, freezing lastAccessed at creation time and making the
        // sweeper reclaim busy sessions on a fixed 30-minute lifetime.
        entry.touch();
        return entry.session();
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

    /** Returns a snapshot of all currently live sessions. */
    public Collection<AgentSession> activeSessions() {
        return sessions.values().stream().map(SessionEntry::session).toList();
    }

    /**
     * Returns how many sessions are currently live — O(1), and without the per-session
     * {@code List} that {@link #activeSessions()} allocates for a caller that only wants
     * the count.
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
     */
    public Optional<AgentSession> find(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        SessionEntry entry = sessions.get(sessionId.value());
        return entry == null ? Optional.empty() : Optional.of(entry.session());
    }

    /**
     * Returns a snapshot of all active sessions with metadata for observability.
     * Each entry is an immutable map containing: session_id, turn_count,
     * last_accessed_at (epoch millis), ttl_remaining_ms, state.
     */
    public List<Map<String, Object>> listActive() {
        long now = System.nanoTime();
        return sessions.entrySet().stream()
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

    /** Shuts down the sweeper and releases every live session's wiring before clearing the map. */
    public void shutdown() {
        sweeper.shutdownNow();
        // Drain key by key rather than clear()-then-close: with clear(), a session created
        // concurrently between the clear and the close loop would be dropped from the map
        // with its leases never released.
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
     */
    private void evictStale() {
        long now = System.nanoTime();
        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, SessionEntry> e : sessions.entrySet()) {
            SessionEntry entry = e.getValue();
            if (now - entry.lastAccessedNanos() >= sessionTtlNanos && !entry.session().isBusy()) {
                staleKeys.add(e.getKey());
            }
        }
        for (String key : staleKeys) {
            removeAndClose(key);
        }
    }

    /**
     * Removes {@code key} and releases the removed session's wiring. The map removal is the
     * single point of ownership transfer: only the caller that observes a non-null return
     * from {@code remove} closes the wiring, so two concurrent teardown paths
     * ({@code invalidate} racing the sweeper) can never double-close.
     */
    private void removeAndClose(String key) {
        SessionEntry removed = sessions.remove(key);
        if (removed != null) {
            removed.session().wiring().close();
        }
    }

    /**
     * Mutable holder for a session's last-access timestamps. Deliberately a class and not a
     * record: {@link #touch()} must update the live entry the map already holds, and both
     * fields are {@code volatile} so the sweeper thread always observes the latest write
     * without any locking on the {@code getOrCreate} hot path.
     */
    private static final class SessionEntry {
        private final AgentSession session;
        /** Monotonic, authoritative for TTL arithmetic. */
        private volatile long lastAccessedNanos;
        /** Wall clock, for {@link #listActive()} display only — never used to measure elapsed time. */
        private volatile long lastAccessedEpochMillis;

        SessionEntry(AgentSession session) {
            this.session = session;
            touch();
        }

        void touch() {
            this.lastAccessedNanos       = System.nanoTime();
            this.lastAccessedEpochMillis = System.currentTimeMillis();
        }

        AgentSession session()          { return session; }
        long lastAccessedNanos()        { return lastAccessedNanos; }
        long lastAccessedEpochMillis()  { return lastAccessedEpochMillis; }
    }
}
