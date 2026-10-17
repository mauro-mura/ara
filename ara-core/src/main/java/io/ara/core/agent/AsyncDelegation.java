package io.ara.core.agent;

/**
 * Lets an agent start sub-tasks in the background and carry on, instead of waiting for each
 * delegate. Present on an agent's {@link ExecutionConfig} only if it opted in; {@code null}
 * there means off, and an agent that is off sees no trace of the feature (no extra
 * parameter on {@code delegate_task}, no extra tools).
 *
 * <p>Immutable.
 *
 * @param maxConcurrent  how many background sub-tasks may be running at once for one run; a
 *                       further start is refused, not queued, so a model that opens tasks
 *                       without collecting them finds out at once. Counts running tasks only,
 *                       not finished ones still waiting to be delivered.
 * @param maxResultChars longest result delivered back from a background sub-task; longer ones
 *                       are cut with the number of characters missing. Waited delegation is
 *                       not limited by this: its observation stays whole, as before.
 */
public record AsyncDelegation(int maxConcurrent, int maxResultChars) {

    public static final int DEFAULT_MAX_CONCURRENT  = 4;
    public static final int DEFAULT_MAX_RESULT_CHARS = 8_000;

    public AsyncDelegation {
        if (maxConcurrent < 1)  throw new IllegalArgumentException("maxConcurrent must be >= 1");
        if (maxResultChars < 1) throw new IllegalArgumentException("maxResultChars must be >= 1");
    }

    public static AsyncDelegation defaults() {
        return new AsyncDelegation(DEFAULT_MAX_CONCURRENT, DEFAULT_MAX_RESULT_CHARS);
    }
}
