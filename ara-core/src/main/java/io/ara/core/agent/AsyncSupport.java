package io.ara.core.agent;

/**
 * Whether an agent accepts being delegated to in the background, declared by the agent that
 * is delegated <em>to</em> (the recipient) — the caller only decides whether it may delegate
 * that way at all, see {@link AsyncDelegation}.
 *
 * <p>The recipient declares it because only it knows what it is for. An agent whose value is
 * the conversation it keeps with its caller is {@link #FORBIDDEN} from being a background
 * task, since a background task gets a session of its own and so no history; an agent whose
 * work outlasts the synchronous timeout is {@link #REQUIRED} to be one. The three values are
 * the ones MCP gives a tool for task-augmented calls ({@code taskSupport}).
 *
 * <p>{@code null} where this is read means {@link #OPTIONAL}: the caller's model chooses.
 */
public enum AsyncSupport {

    /** Only waited delegation; a request to run it in the background is refused. */
    FORBIDDEN,

    /** Either; the delegating model chooses per call. The default. */
    OPTIONAL,

    /**
     * Only in the background: a waited call is run in the background anyway, and the
     * delegating model is told so, when its agent allows background delegation at all.
     */
    REQUIRED
}
