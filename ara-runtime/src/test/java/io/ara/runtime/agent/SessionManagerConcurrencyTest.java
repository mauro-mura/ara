package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.concurrency.PinningProbe;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.wiring.AgentWiring;
import io.ara.runtime.wiring.Lease;
import io.ara.runtime.wiring.WiringFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 N1 row 2, §4 U24 — regression tests.
 *
 * <p>Before U24, {@link SessionManager#getOrCreate}'s single-creation-per-sessionId guarantee
 * came from running the whole {@code wiringFactory.build} call — a real MCP connection open in
 * production — inside {@code ConcurrentHashMap.computeIfAbsent}'s own mapping function, which
 * holds the map's internal per-bin lock for as long as the function runs; a virtual thread that
 * blocks on real I/O while inside that lock pins its OS carrier on this project's JDK 21 target.
 * U24 moves the build outside {@code computeIfAbsent}, onto a per-entry {@link
 * java.util.concurrent.locks.ReentrantLock} ({@code SessionEntry.buildLock}) this class owns.
 * These three tests check, in order: (1) the single-creation guarantee itself still holds under
 * a real race, (2) the pinning risk is actually gone (empirically, via {@link PinningProbe}),
 * and (3) the new race window this refactor opens — a teardown racing an in-flight first
 * build — is closed correctly (exactly one close, and {@code getOrCreate} retries rather than
 * returning a torn-down session).
 */
class SessionManagerConcurrencyTest {

    private static AgentConfig dummyConfig(String agentType) {
        return AgentConfig.defaults().agentType(agentType).primaryLlm(LlmProfile.of("m")).build();
    }

    private static LlmClient noopLlm() {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw new UnsupportedOperationException();
            }
            @Override public String providerId() { return "noop"; }
        };
    }

    @Test
    void getOrCreate_manyConcurrentCallersForABrandNewSessionId_buildExactlyOnceAndShareTheSameSession()
            throws Exception {
        AtomicInteger builds = new AtomicInteger();
        WiringFactory slowBuildFactory = config -> {
            builds.incrementAndGet();
            try {
                Thread.sleep(50);   // widens the race window every caller must collapse into one build
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentWiring(config, noopLlm(), ToolRegistry.empty(), List.of());
        };
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), slowBuildFactory, SessionStore.noop());
        SessionId sessionId = SessionId.of("raced-new-session");
        AgentConfig config = dummyConfig("raced-new-session");

        int callers = 32;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<AgentSession>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return manager.getOrCreate(sessionId, config);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "every caller must have started before the race begins");
            go.countDown();

            Set<AgentSession> distinctSessions = new HashSet<>();
            for (Future<AgentSession> future : futures) {
                distinctSessions.add(future.get(5, TimeUnit.SECONDS));
            }

            assertEquals(1, builds.get(),
                    "a brand-new sessionId must be built exactly once, however many callers race for it");
            assertEquals(1, distinctSessions.size(),
                    "every racing caller must observe the same session instance, not one each");
        } finally {
            executor.shutdown();
            manager.shutdown();
        }
    }

    @Test
    void getOrCreate_withASlowWiringFactoryBuild_doesNotPinAnyCarrier() throws InterruptedException {
        Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        WiringFactory gatedFactory = config -> {
            CountDownLatch gate = gates.get(config.agentType());
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentWiring(config, noopLlm(), ToolRegistry.empty(), List.of());
        };
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), gatedFactory, SessionStore.noop());

        try {
            int cpus = Runtime.getRuntime().availableProcessors();
            List<PinningProbe.Attempt> attempts = new ArrayList<>();
            for (int i = 0; i < cpus + 2; i++) {
                String id = "pinning-probe-session-" + i;
                SessionId sessionId = SessionId.of(id);
                AgentConfig config = dummyConfig(id);
                // Every attempt is a *distinct* sessionId, so this probes computeIfAbsent's own
                // slow-mapping-function risk (N1 row 2), not SessionEntry.buildLock's same-id
                // serialization (which is expected and, being a ReentrantLock, never pins either).
                attempts.add(PinningProbe.Attempt.onLatch(gate -> {
                    gates.put(id, gate);
                    manager.getOrCreate(sessionId, config);
                }));
            }

            PinningProbe.Result result = PinningProbe.probe(attempts);

            assertFalse(result.pinningObserved(),
                    "N1 row 2 (getOrCreate -> wiringFactory.build) must not pin a carrier after U24 "
                            + "(the build runs under SessionEntry.buildLock, a ReentrantLock, not inside "
                            + "ConcurrentHashMap.computeIfAbsent's own mapping function)");
        } finally {
            manager.shutdown();
        }
    }

    /** A {@link WiringFactory} whose first build blocks until released, so a test can reliably land a teardown while it is in flight. */
    private static final class BlockingFirstBuildWiringFactory implements WiringFactory {
        final CountDownLatch buildStarted = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final AtomicInteger builds = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        @Override
        public AgentWiring build(AgentConfig config) {
            int n = builds.incrementAndGet();
            if (n == 1) {
                buildStarted.countDown();
                try {
                    assertTrue(proceed.await(10, TimeUnit.SECONDS), "test must release the first build");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Lease<Object> lease = new Lease<>(new Object(), releases::incrementAndGet);
            return new AgentWiring(config, noopLlm(), ToolRegistry.empty(), List.of(lease));
        }
    }

    /**
     * Reproduces the race window U24 itself opens (see {@link SessionManager#getOrCreate}'s own
     * javadoc): {@code invalidate} lands while a brand-new session's first build is still
     * in-flight. Expected outcome: the torn-down build's wiring is closed exactly once as soon
     * as it finishes (never published), and {@code getOrCreate} transparently retries with a
     * fresh build rather than handing its caller a session that was already invalidated.
     *
     * <p>{@code invalidate} itself must run on its own thread here, not inline: {@code
     * takeForClose} deliberately contends on the same {@code buildLock} the in-flight build
     * holds (needed so "is it built yet" is answered exactly once — see {@code SessionEntry}'s
     * own javadoc), so a caller racing a slow first build blocks (parks, not pins) until that
     * build finishes — exactly the same blocking a caller of {@code invalidate} would already
     * have seen pre-U24, via {@code ConcurrentHashMap}'s own bin lock instead.
     */
    @Test
    void invalidate_racingAnInFlightFirstBuild_closesItExactlyOnce_andGetOrCreateRetries() throws Exception {
        BlockingFirstBuildWiringFactory wiringFactory = new BlockingFirstBuildWiringFactory();
        SessionManager manager = new SessionManager(sid -> new InMemoryMemoryManager(), wiringFactory, SessionStore.noop());
        SessionId sessionId = SessionId.of("torn-down-during-build");

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<AgentSession> buildFuture = executor.submit(() -> manager.getOrCreate(sessionId, dummyConfig("t")));

            assertTrue(wiringFactory.buildStarted.await(5, TimeUnit.SECONDS), "the first build must have started");

            // invalidate() blocks on the same buildLock the in-flight build holds, so it must
            // race concurrently with proceed.countDown() below, not run before it.
            Future<?> invalidateFuture = executor.submit(() -> {
                manager.invalidate(sessionId);
                return null;
            });
            Thread.sleep(200);   // let invalidateFuture actually reach and park on buildLock
            wiringFactory.proceed.countDown();   // let the (now torn-down) first build finish

            invalidateFuture.get(5, TimeUnit.SECONDS);
            AgentSession session = buildFuture.get(5, TimeUnit.SECONDS);

            assertEquals(2, wiringFactory.builds.get(),
                    "the torn-down build must never be reused; getOrCreate must retry with a fresh build");
            assertEquals(1, wiringFactory.releases.get(),
                    "the torn-down build's wiring must be closed exactly once as soon as it finishes building");
            assertTrue(manager.find(sessionId).isPresent(), "the retried build is the one actually published");
            assertSame(session, manager.find(sessionId).get(),
                    "the caller must receive the same session that ended up published, never the torn-down one");

            manager.shutdown();
            assertEquals(2, wiringFactory.releases.get(), "shutdown must also close the live (second) session's wiring");
        } finally {
            executor.shutdown();
        }
    }

    // Note (P5/U16, 2026-09-23): getOrCreate's fast path — a volatile read of an already-
    // built SessionEntry.session — was fixed to re-verify the entry is still the map's
    // current value before trusting it (SessionEntry.takeForClose() marks an entry torn
    // down but never nulls its `session` field, so a stale SessionEntry reference could
    // otherwise still read a since-closed session). No regression test for this specific
    // fix: the race window is a couple of CPU instructions wide (between computeIfAbsent
    // returning and the very next line's field read, no I/O or blocking call in between to
    // land a concurrent teardown in), and a sustained concurrent-churn stress attempt (16
    // readers hammering getOrCreate against a concurrent invalidate loop for 500ms, well
    // over a million combined calls) did not reproduce the bug even once against the
    // pre-fix code across repeated runs — confirmed by reverting the fix locally and
    // re-running it, per this project's own verification convention. Correctness here
    // rests on the code-reading analysis in this method's own javadoc, not on a test.
}
