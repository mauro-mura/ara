package io.ara.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-flight bookkeeping of {@link RuntimeLifecycle#trackedExecutor()}. */
class RuntimeLifecycleTrackedExecutorTest {

    @Test
    void workIsCountedWhileItRuns_andReleasedWhenItEnds() throws Exception {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle("tracked", 5);
        lifecycle.start();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        lifecycle.trackedExecutor().execute(() -> {
            running.countDown();
            await(release);
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertEquals(1, lifecycle.inFlightCount());

        release.countDown();
        assertTrue(lifecycle.awaitQuiescence(5, TimeUnit.SECONDS));
        assertEquals(0, lifecycle.inFlightCount());
        lifecycle.stop();
    }

    @Test
    void workThatThrows_stillReleasesItsCount() throws Exception {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle("tracked-throw", 5);
        lifecycle.start();

        lifecycle.trackedExecutor().execute(() -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(lifecycle.awaitQuiescence(5, TimeUnit.SECONDS),
                "a delegate that blows up must not leave the runtime believing it is still running");
        assertEquals(0, lifecycle.inFlightCount());
        lifecycle.stop();
    }

    @Test
    void afterStop_workIsRefused_andNothingIsCounted() {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle("tracked-stopped", 5);
        lifecycle.start();
        Executor tracked = lifecycle.trackedExecutor();
        lifecycle.stop();

        // A stopped runtime is not resurrected by a delegation: the lifecycle refuses, as it
        // does for runtime.submit, and the refused work never touches the counter.
        assertThrows(IllegalStateException.class, () -> tracked.execute(() -> { }));
        assertEquals(0, lifecycle.inFlightCount());
    }

    @Test
    void stop_waitsForWorkStartedOnTheTrackedExecutor() throws Exception {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle("tracked-drain", 5);
        lifecycle.start();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        lifecycle.trackedExecutor().execute(() -> {
            running.countDown();
            sleepBriefly();
            finished.countDown();
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));

        lifecycle.stop();

        assertEquals(0, finished.getCount(), "stop() returned while the delegate was still running");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
