package io.ara.runtime.concurrency;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Detects virtual-thread carrier pinning empirically, without JVM flags or a forked process:
 * {@code Runtime.availableProcessors()} is the default virtual-thread carrier pool size
 * ({@code jdk.virtualThreadScheduler.parallelism}'s own default, per the JDK), so saturating
 * that many concurrent virtual threads with the operation under test and then checking whether
 * one more, independent virtual thread — the <em>canary</em> — can still be scheduled is enough
 * to observe pinning if it happens.
 *
 * <p>The distinction this probe measures: a carrier occupied by a virtual thread that is merely
 * <em>parked</em> (blocked without pinning) is released back to the scheduler for the duration
 * of the block, so the canary gets a carrier promptly regardless of how many other virtual
 * threads are blocked. A carrier occupied by a <em>pinned</em> virtual thread is not released
 * until the pinning call itself returns — with every carrier pinned, the canary cannot be
 * scheduled at all, however trivial its own body is, until one of the saturating calls is
 * released.
 *
 * <h2>Correct on this project's target JDK</h2>
 * Verified against {@code docs/analysis/concurrency-hardening.md} (2026-09-22): JEP 491, which
 * removes pinning from {@code synchronized}, landed in JDK 24. This project compiles with
 * {@code <maven.compiler.release>21</maven.compiler.release>} and no {@code --enable-preview} —
 * on JDK 21 a virtual thread blocked inside a monitor pins its carrier for <em>any</em> blocking
 * operation, not only I/O. That is what makes a plain {@link CountDownLatch#await()} a valid,
 * fast, no-real-I/O stand-in for the MCP/HTTP calls the actual N1 findings block on: a
 * {@code synchronized} block that blocks on a latch pins exactly as a {@code synchronized}
 * block that blocks on a socket read does.
 *
 * <h2>What this deliberately does not do</h2>
 * No {@code jdk.tracePinnedThreads} log parsing (format is a diagnostic aid, not a contract,
 * and differs across JDK builds) and no {@code jdk.virtualThreadScheduler.parallelism} override
 * (would require a forked JVM to take effect, since the property is read once at startup) —
 * this probe works in-process, in any environment, using only the carrier count the JVM already
 * has.
 */
public final class PinningProbe {

    private PinningProbe() {
    }

    /**
     * One saturating call: {@code blockingCall} must engage whatever synchronization is under
     * test and then block until {@code release} is invoked by the probe. The probe calls
     * {@code release} exactly once per attempt, always — whether or not pinning was observed —
     * so a probe never leaks a blocked virtual thread into the rest of a test run.
     */
    public record Attempt(Runnable blockingCall, Runnable release) {
        public Attempt {
            Objects.requireNonNull(blockingCall, "blockingCall must not be null");
            Objects.requireNonNull(release, "release must not be null");
        }

        /** The common case: block on a fresh latch the probe itself never touches otherwise. */
        public static Attempt onLatch(java.util.function.Consumer<CountDownLatch> engage) {
            CountDownLatch gate = new CountDownLatch(1);
            return new Attempt(() -> engage.accept(gate), gate::countDown);
        }
    }

    /**
     * @param pinningObserved  {@code true} when the canary did not run within {@code canaryWait}
     *                         — every carrier was occupied by a pinned saturating call
     * @param saturatingThreads how many concurrent attempts were used (always
     *                          {@code >= Runtime.availableProcessors()})
     * @param canaryWait       the bound the canary was given
     */
    public record Result(boolean pinningObserved, int saturatingThreads, Duration canaryWait) {
    }

    private static final Duration DEFAULT_SETTLE = Duration.ofMillis(300);
    private static final Duration DEFAULT_CANARY_WAIT = Duration.ofSeconds(1);

    /** {@link #probe(List, Duration, Duration)} with this probe's own defaults. */
    public static Result probe(List<Attempt> attempts) throws InterruptedException {
        return probe(attempts, DEFAULT_SETTLE, DEFAULT_CANARY_WAIT);
    }

    /**
     * Starts one virtual thread per {@code attempts} entry (must be at least
     * {@code Runtime.availableProcessors()} — fewer could never occupy every carrier, and the
     * result would be meaningless), waits {@code settle} for them to actually reach their
     * blocking point (starting a virtual thread only proves the {@code Runnable} began running,
     * not that it reached the {@code blockingCall}'s own block — see the class javadoc's
     * "what this deliberately does not do" for why that is a timing heuristic and not a
     * synchronized handshake: keeping the probe itself free of any risk of pinning ruled out a
     * lock-based rendezvous here), then starts one more virtual thread — the canary — and waits
     * up to {@code canaryWait} for it. Every attempt is released in a {@code finally}, so the
     * saturating threads are unblocked and this method returns cleanly whether pinning was
     * observed or not.
     */
    public static Result probe(List<Attempt> attempts, Duration settle, Duration canaryWait)
            throws InterruptedException {
        Objects.requireNonNull(attempts, "attempts must not be null");
        Objects.requireNonNull(settle, "settle must not be null");
        Objects.requireNonNull(canaryWait, "canaryWait must not be null");
        int cpus = Runtime.getRuntime().availableProcessors();
        if (attempts.size() < cpus) {
            throw new IllegalArgumentException("need at least " + cpus
                    + " saturating attempts (Runtime.availableProcessors()) to occupy every carrier, got "
                    + attempts.size());
        }

        for (Attempt attempt : attempts) {
            Thread.ofVirtual().start(attempt.blockingCall());
        }
        Thread.sleep(settle.toMillis());

        CountDownLatch canaryDone = new CountDownLatch(1);
        Thread.ofVirtual().start(canaryDone::countDown);

        boolean canaryRanInTime;
        try {
            canaryRanInTime = canaryDone.await(canaryWait.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            for (Attempt attempt : attempts) {
                attempt.release().run();
            }
        }
        return new Result(!canaryRanInTime, attempts.size(), canaryWait);
    }
}
