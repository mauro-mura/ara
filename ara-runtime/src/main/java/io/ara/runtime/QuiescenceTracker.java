package io.ara.runtime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks in-flight tasks submitted via {@link AraRuntime#submit}.
 * Thread-safe: multiple threads may call {@code taskStarted}/{@code taskFinished}
 * concurrently. {@code awaitQuiescence} blocks until the count drops to zero
 * or the timeout elapses.
 */
final class QuiescenceTracker {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private int inFlight = 0;

    /** Called before a task is handed to the executor. */
    void taskStarted() {
        lock.lock();
        try {
            inFlight++;
        } finally {
            lock.unlock();
        }
    }

    /** Called when a task completes (normally or exceptionally). */
    void taskFinished() {
        lock.lock();
        try {
            if (--inFlight == 0) {
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until all in-flight tasks complete or the timeout elapses.
     *
     * @return true if quiescence was reached, false on timeout
     */
    boolean awaitQuiescence(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        lock.lock();
        try {
            while (inFlight > 0) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) return false;
                idle.await(remainingNanos, TimeUnit.NANOSECONDS);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Current number of in-flight tasks (for diagnostics / logging). */
    int inFlightCount() {
        lock.lock();
        try {
            return inFlight;
        } finally {
            lock.unlock();
        }
    }
}
