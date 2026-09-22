package io.ara.runtime.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-test of {@link PinningProbe} against two minimal, known-correct controls before any
 * N1 finding relies on it: a block-under-{@code synchronized} (must pin, on this project's
 * JDK 21 target — JEP 491 is JDK 24) and the same block-under-{@link ReentrantLock} (must not,
 * {@code ReentrantLock} being exactly the primitive N1's own remediation units switch to
 * because it does not pin). If either control disagreed with its own well-established JDK
 * semantics, the probe itself — not the code it is meant to check — would be the thing to fix.
 */
class PinningProbeTest {

    private static final Object MONITOR = new Object();
    private static final ReentrantLock REENTRANT_LOCK = new ReentrantLock();

    @Test
    void aSynchronizedBlockThatBlocksInsideItPinsEveryCarrier() throws InterruptedException {
        List<PinningProbe.Attempt> attempts = saturatingAttempts(gate -> {
            synchronized (MONITOR) {
                await(gate);
            }
        });

        PinningProbe.Result result = PinningProbe.probe(attempts);

        assertTrue(result.pinningObserved(),
                "a virtual thread blocked inside synchronized must pin its carrier on JDK 21 "
                        + "(JEP 491 — which removes this — is JDK 24, not this project's target)");
    }

    @Test
    void theSameBlockUnderAReentrantLockDoesNotPinAnyCarrier() throws InterruptedException {
        List<PinningProbe.Attempt> attempts = saturatingAttempts(gate -> {
            REENTRANT_LOCK.lock();
            try {
                await(gate);
            } finally {
                REENTRANT_LOCK.unlock();
            }
        });

        PinningProbe.Result result = PinningProbe.probe(attempts);

        assertFalse(result.pinningObserved(),
                "ReentrantLock parks the virtual thread instead of pinning the carrier — the "
                        + "canary must still be scheduled promptly");
    }

    @Test
    void tooFewAttemptsIsRejectedRatherThanGivingAMeaninglessResult() {
        List<PinningProbe.Attempt> tooFew = saturatingAttempts(PinningProbeTest::await)
                .subList(0, 1);

        assertTrue(assertThrowsIllegalArgument(() -> PinningProbe.probe(tooFew))
                .getMessage().contains("availableProcessors"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static List<PinningProbe.Attempt> saturatingAttempts(
            java.util.function.Consumer<CountDownLatch> engage) {
        int cpus = Runtime.getRuntime().availableProcessors();
        List<PinningProbe.Attempt> attempts = new ArrayList<>();
        // +2 margin over the bare carrier count: see PinningProbe's own javadoc on why the
        // settle wait is a heuristic, not a rendezvous — a couple of spare saturating threads
        // keep the probe honest even if some unrelated virtual thread transiently holds a
        // carrier at the moment this test runs.
        for (int i = 0; i < cpus + 2; i++) {
            attempts.add(PinningProbe.Attempt.onLatch(engage));
        }
        return attempts;
    }

    private static void await(CountDownLatch gate) {
        try {
            // Never actually reached by the probe within the test's own timeout — the probe
            // releases every attempt in its finally block before returning, real or not.
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static IllegalArgumentException assertThrowsIllegalArgument(
            org.junit.jupiter.api.function.Executable executable) {
        try {
            executable.execute();
        } catch (IllegalArgumentException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError("expected IllegalArgumentException, got " + t, t);
        }
        throw new AssertionError("expected IllegalArgumentException, nothing was thrown");
    }
}
