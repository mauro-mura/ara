package io.ara.core.agent;

/**
 * Governs what happens when a task is submitted to a {@code session} that is
 * already executing another task on the same agent (ADR-016).
 *
 * <p>Different sessions always run concurrently regardless of this policy; it
 * only affects two tasks that target the <em>same</em> {@link SessionId}.
 */
public enum SessionBusyPolicy {

    /**
     * Reject the incoming task immediately with a "Session busy" failure.
     * This is the historical default: callers are expected to serialize their
     * own requests per session.
     */
    REJECT,

    /**
     * Queue the incoming task and run it after the in-flight one completes.
     * Tasks on the same session execute serially in submission order (FIFO);
     * the caller's thread blocks until its turn (or its future completes).
     */
    ENQUEUE
}
