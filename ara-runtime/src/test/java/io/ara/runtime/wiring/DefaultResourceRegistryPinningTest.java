package io.ara.runtime.wiring;

import io.ara.runtime.concurrency.PinningProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * N1 / {@code docs/analysis/concurrency-hardening.md} §3 row 1, §4 U23 — regression test.
 *
 * <p>{@code factory.apply} runs inside {@code Entry.lock} in
 * {@link DefaultResourceRegistry#pinOrCreate} (via {@code publishLocked}). Before U23,
 * {@code Entry.lock} was a plain monitor ({@code synchronized}), and a virtual thread blocked
 * inside it pinned its carrier on this project's JDK 21 target — this exact test, with
 * {@code assertTrue}, reproduced that empirically (verified 2026-09-22, before this fix).
 * U23 changed {@code Entry.lock} to a {@link java.util.concurrent.locks.ReentrantLock}: the
 * same blocking call, in the same place, under the same per-id mutual exclusion, no longer
 * pins — {@link PinningProbe} saturates every carrier with a slow {@code factory} (the
 * stand-in for a slow MCP connection open) and checks that an unrelated virtual thread — the
 * canary — can still be scheduled while they are all in flight.
 */
class DefaultResourceRegistryPinningTest {

    @Test
    void pinOrCreateWithASlowFactoryDoesNotPinAnyCarrier() throws InterruptedException {
        DefaultResourceRegistry<CountDownLatch, String> registry =
                new DefaultResourceRegistry<>(DefaultResourceRegistryPinningTest::awaitQuietly, resource -> { });

        int cpus = Runtime.getRuntime().availableProcessors();
        List<PinningProbe.Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < cpus + 2; i++) {
            String id = "pinning-probe-" + i;
            attempts.add(PinningProbe.Attempt.onLatch(gate -> registry.pinOrCreate(id, () -> gate)));
        }

        PinningProbe.Result result = PinningProbe.probe(attempts);

        assertFalse(result.pinningObserved(),
                "N1 row 1 (pinOrCreate -> publishLocked -> factory.apply) must not pin a "
                        + "carrier after U23 (Entry.lock is a ReentrantLock, not a monitor)");
    }

    private static String awaitQuietly(CountDownLatch gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "resource";
    }
}
