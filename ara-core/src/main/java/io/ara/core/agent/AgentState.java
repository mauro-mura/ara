package io.ara.core.agent;

/**
 * All possible lifecycle states of an {@link AraAgent} instance.
 *
 * <p>The valid transitions are:
 * <pre>
 *   IDLE ──► PLANNING ──► EXECUTING ──► DONE
 *                              │
 *                              └──► FAILED
 *
 *   DONE  ──► IDLE   (instance reuse)
 *   FAILED ──► IDLE  (instance reuse after reflexion)
 * </pre>
 */
public enum AgentState {

    /**
     * The agent is alive but not processing any task.
     * This is both the initial state and the state reached after completing or
     * failing a task when the instance is kept alive for reuse.
     */
    IDLE,

    /**
     * The agent is analysing the incoming task and selecting an execution strategy
     * (ReAct, Plan-and-Execute, Tree-of-Thoughts, etc.) via the {@code ExecutionPlanner}.
     */
    PLANNING,

    /**
     * The agent is actively running its ReAct loop: calling the LLM, dispatching
     * tool calls, and evaluating intermediate results.
     */
    EXECUTING,

    /**
     * The agent has successfully completed its task and has emitted a final response.
     * From here the instance may transition back to {@link #IDLE} for reuse, or
     * be terminated by the {@code AgentRegistry}.
     */
    DONE,

    /**
     * The agent encountered an unrecoverable error, exceeded the maximum number of
     * ReAct iterations, or was rejected by a contract output processor.
     *
     * <p>A reflexion event is written to episodic memory so that future attempts can
     * learn from this failure. The instance may transition back to {@link #IDLE} for retry.
     */
    FAILED
}
