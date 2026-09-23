package io.ara.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P4, §4 U14 — regression test.
 *
 * <p>{@code RuntimeLifecycle.shutdownExecutor()} used to call {@code shutdownNow()} and
 * return immediately once the graceful {@code awaitTermination} window expired.
 * {@code shutdownNow()} only <em>requests</em> cancellation (interrupts running tasks) — it
 * does not wait for them to actually stop, so {@code stop()} could return, and a caller
 * could treat the runtime as fully torn down, while an interrupted-but-still-unwinding task
 * was still running. Fixed by a second, short, bounded {@code awaitTermination} after
 * {@code shutdownNow()}.
 */
class RuntimeLifecycleShutdownTest {

    @Test
    void stop_waitsForAnInterruptedTaskToActuallyFinish_notJustForShutdownNowToBeCalled() throws Exception {
        // shutdownTimeoutSec=0: the first, graceful awaitTermination times out immediately
        // (the task below is still running), forcing the shutdownNow() + second-wait path.
        RuntimeLifecycle lifecycle = new RuntimeLifecycle("second-await-test", 0);
        lifecycle.start();
        ExecutorService executor = (ExecutorService) lifecycle.getAgentExecutor();

        CountDownLatch taskStarted = new CountDownLatch(1);
        executor.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                // Cooperative, but not instantaneous: simulates real unwind work (closing a
                // connection, flushing state) that takes a moment after the interrupt.
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS), "task did not start within 2s");

        lifecycle.stop();

        assertTrue(executor.isTerminated(),
                "stop() must wait for a shutdownNow()-interrupted task to actually finish "
                        + "unwinding (up to a short bound), not just call shutdownNow() and return");
    }
}
