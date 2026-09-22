package io.ara.runtime;

import io.ara.runtime.concurrency.PinningProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 N1 rows 3/4, §4 U25 — regression test.
 *
 * <p>{@code RuntimeLifecycle.stop()} blocks on {@code awaitTermination} (up to {@code
 * shutdownTimeoutSec} seconds) while holding {@link RuntimeLifecycle#getLock()}; {@code
 * AraRuntime.stop()}/{@code destroyAgent()} block on real MCP-connection close ({@code
 * AgentFactory.destroyPermanently}) while holding the very same lock. Before U25 it was a
 * monitor ({@code synchronized}) — on this project's JDK 21 target, that pins the carrier
 * both while a thread <em>holds</em> a contended monitor and blocks inside it, and while
 * another thread merely <em>waits to enter</em> a contended monitor (unlike a {@link
 * ReentrantLock}, where a thread waiting for a contended lock parks instead). This test
 * mirrors {@code PinningProbeTest}'s own single-shared-lock control (not {@code
 * DefaultResourceRegistryPinningTest}'s per-id one — {@link RuntimeLifecycle#getLock()} is
 * one lock shared runtime-wide, not partitioned by id) directly against the real lock
 * instance {@code RuntimeLifecycle} uses.
 */
class RuntimeLifecyclePinningTest {

    @Test
    void lockDoesNotPinAnyCarrierWhenABlockingCallHoldsIt() throws InterruptedException {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle("pinning-probe", 10);
        ReentrantLock lock = lifecycle.getLock();

        int cpus = Runtime.getRuntime().availableProcessors();
        List<PinningProbe.Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < cpus + 2; i++) {
            attempts.add(PinningProbe.Attempt.onLatch(gate -> {
                lock.lock();
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }));
        }

        PinningProbe.Result result = PinningProbe.probe(attempts);

        assertFalse(result.pinningObserved(),
                "N1 (RuntimeLifecycle.stop() / AraRuntime.stop()+destroyAgent() -> "
                        + "lifecycle.getLock()) must not pin a carrier after U25 "
                        + "(the lock is a ReentrantLock, not a monitor)");
    }
}
