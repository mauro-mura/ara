package io.ara.core.agent;

/**
 * All possible lifecycle states of an {@link AraAgent} instance.
 *
 * <p>The valid transitions are:
 * <pre>
 *   IDLE ──► PLANNING ──► EXECUTING ──► DONE
 *                              │
 *                              ├──► FAILED
 *                              └──► AWAITING_APPROVAL ──► IDLE   (run saved, see below)
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
    FAILED,

    /**
     * The run stopped before a tool call a human must approve, and was saved as a
     * {@link RunCheckpoint} (ADR-048 D2). Not a state the agent sits in: it is the label of the
     * response that reports the suspension, and the agent goes straight on to {@link #IDLE} so
     * the session and its leases are free while nobody has decided yet. The run is continued,
     * not retried, once the approval is answered.
     */
    AWAITING_APPROVAL
}
