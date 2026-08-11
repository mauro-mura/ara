package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.wiring.AgentWiring;
import io.ara.runtime.wiring.Lease;
import io.ara.runtime.wiring.WiringFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies ADR-039's central {@code SessionManager} fix: before ADR-039, {@code
 * invalidate}/{@code evictStale}/{@code shutdown} did a bare map removal with no
 * cleanup of anything the session held. Since a session now pins an {@link
 * AgentWiring} with real leases (LLM transports, MCP connections), every teardown
 * path must release them — this test asserts that release actually happens.
 */
class SessionManagerTest {

    private static AgentConfig dummyConfig() {
        return AgentConfig.defaults().agentType("t").primaryLlm(LlmProfile.of("m")).build();
    }

    /** A {@link WiringFactory} whose every built {@link AgentWiring} holds one lease over a counting resource. */
    private static final class CountingWiringFactory implements WiringFactory {
        final AtomicInteger releases = new AtomicInteger();

        @Override
        public AgentWiring build(AgentConfig config) {
            Lease<Object> lease = new Lease<>(new Object(), releases::incrementAndGet);
            LlmClient noopLlm = new LlmClient() {
                @Override public io.ara.core.llm.LlmCompletion complete(
                        List<io.ara.core.llm.LlmMessage> messages, io.ara.core.llm.LlmCallContext context) {
                    throw new UnsupportedOperationException();
                }
                @Override public String providerId() { return "noop"; }
            };
            return new AgentWiring(config, noopLlm, ToolRegistry.empty(), List.of(lease));
        }
    }

    @Test
    void invalidate_releasesTheSessionsWiringLeases() {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory, SessionStore.noop());
        SessionId sessionId = SessionId.of("s1");

        manager.getOrCreate(sessionId, dummyConfig());
        assertEquals(0, wiringFactory.releases.get(), "wiring must stay open while the session is alive");

        manager.invalidate(sessionId);
        assertEquals(1, wiringFactory.releases.get(), "invalidate must release the session's wiring leases");
    }

    @Test
    void invalidate_unknownSessionId_isANoOp() {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory, SessionStore.noop());

        manager.invalidate(SessionId.of("never-existed"));   // must not throw
        assertEquals(0, wiringFactory.releases.get());
    }

    @Test
    void shutdown_releasesEveryLiveSessionsWiringLeases() {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory, SessionStore.noop());

        manager.getOrCreate(SessionId.of("s1"), dummyConfig());
        manager.getOrCreate(SessionId.of("s2"), dummyConfig());
        manager.getOrCreate(SessionId.of("s3"), dummyConfig());

        manager.shutdown();

        assertEquals(3, wiringFactory.releases.get(), "shutdown must release every live session's wiring, not just clear the map");
        assertTrue(manager.activeSessions().isEmpty());
    }

    @Test
    void getOrCreate_secondCallForSameSessionId_reusesTheFirstWiring_doesNotBuildTwice() {
        AtomicInteger builds = new AtomicInteger();
        WiringFactory countingBuildFactory = config -> {
            builds.incrementAndGet();
            return new AgentWiring(config, new LlmClient() {
                @Override public io.ara.core.llm.LlmCompletion complete(
                        List<io.ara.core.llm.LlmMessage> messages, io.ara.core.llm.LlmCallContext context) {
                    throw new UnsupportedOperationException();
                }
                @Override public String providerId() { return "noop"; }
            }, ToolRegistry.empty(), List.of());
        };
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), countingBuildFactory, SessionStore.noop());
        SessionId sessionId = SessionId.of("reused");

        AgentSession first  = manager.getOrCreate(sessionId, dummyConfig());
        AgentSession second = manager.getOrCreate(sessionId, dummyConfig());

        assertEquals(1, builds.get(), "wiring must be built once per session, not once per getOrCreate call");
        assertEquals(first.wiring(), second.wiring());
    }

    @Test
    void find_doesNotCreateASessionAsASideEffect() {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory, SessionStore.noop());

        assertFalse(manager.find(SessionId.of("never-touched")).isPresent());
    }

    /**
     * Regression: {@code getOrCreate} used to end in {@code .touch().session()} on an
     * immutable {@code record}, so the replacement entry {@code touch()} built was thrown
     * away and {@code lastAccessed} stayed pinned to creation time. The TTL therefore
     * measured total lifetime instead of idle time, and the sweeper tore down actively used
     * sessions — releasing their wiring leases — on a fixed schedule.
     */
    @Test
    void getOrCreate_resetsTheIdleClock_soAnActiveSessionIsNeverEvicted() throws Exception {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory,
                SessionStore.noop(), Duration.ofMillis(200), Duration.ofMillis(20));
        SessionId sessionId = SessionId.of("kept-alive");

        AgentSession first = manager.getOrCreate(sessionId, dummyConfig());

        // Keep using the session for well over one TTL, the way a live conversation would.
        for (int i = 0; i < 12; i++) {
            Thread.sleep(50);
            assertSame(first, manager.getOrCreate(sessionId, dummyConfig()),
                    "an actively used session must never be evicted and rebuilt");
        }
        assertEquals(0, wiringFactory.releases.get(), "no lease may be released while the session is in use");

        manager.shutdown();
    }

    @Test
    void evictStale_removesASessionLeftIdleLongerThanTheTtl() throws Exception {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory,
                SessionStore.noop(), Duration.ofMillis(100), Duration.ofMillis(20));

        manager.getOrCreate(SessionId.of("abandoned"), dummyConfig());
        assertEquals(1, manager.activeSessionCount());

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (manager.activeSessionCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(0, manager.activeSessionCount(), "an idle session must be reclaimed after its TTL");
        assertEquals(1, wiringFactory.releases.get(), "eviction must release the evicted session's leases");

        manager.shutdown();
    }

    /**
     * A task running longer than the TTL must not have its LLM transport / MCP leases pulled
     * out from under it: {@code evictStale} skips any session whose execution lock is held.
     */
    @Test
    void evictStale_skipsASessionWithATaskInFlight() throws Exception {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory,
                SessionStore.noop(), Duration.ofMillis(100), Duration.ofMillis(20));

        AgentSession session = manager.getOrCreate(SessionId.of("long-running"), dummyConfig());
        session.executionLock().lock();          // simulate a task in flight
        try {
            Thread.sleep(400);                   // several TTLs and sweeps go by
            assertEquals(1, manager.activeSessionCount(), "a busy session must not be evicted");
            assertEquals(0, wiringFactory.releases.get(), "a busy session's leases must stay open");
        } finally {
            session.executionLock().unlock();
        }

        manager.shutdown();
    }

    @Test
    void activeSessionCount_matchesTheNumberOfLiveSessions() {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory, SessionStore.noop());

        assertEquals(0, manager.activeSessionCount());
        manager.getOrCreate(SessionId.of("s1"), dummyConfig());
        manager.getOrCreate(SessionId.of("s2"), dummyConfig());
        manager.getOrCreate(SessionId.of("s1"), dummyConfig());   // same id — not a new session
        assertEquals(2, manager.activeSessionCount());

        manager.invalidate(SessionId.of("s1"));
        assertEquals(1, manager.activeSessionCount());

        manager.shutdown();
    }

    @Test
    void listActive_reportsAShrinkingTtlThatNeverGoesNegative() throws Exception {
        CountingWiringFactory wiringFactory = new CountingWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory,
                SessionStore.noop(), Duration.ofMillis(300), Duration.ofHours(1));   // sweeper effectively off

        manager.getOrCreate(SessionId.of("s1"), dummyConfig());
        long firstReading = (long) manager.listActive().get(0).get("ttl_remaining_ms");

        Thread.sleep(120);
        long secondReading = (long) manager.listActive().get(0).get("ttl_remaining_ms");

        assertTrue(secondReading < firstReading, "ttl_remaining_ms must decrease as the session sits idle");
        assertTrue(secondReading >= 0, "ttl_remaining_ms must never go negative");

        manager.shutdown();
    }
}
