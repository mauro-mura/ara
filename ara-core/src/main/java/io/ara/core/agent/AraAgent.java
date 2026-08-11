package io.ara.core.agent;

import io.ara.core.common.AgentId;

/**
 * The central contract that every agent in the ARA platform must satisfy.
 *
 * <p>Deliberately minimal. Derived, stateless conveniences ({@code ask}, {@code askText},
 * {@code executeAsync}, {@code agentCard}) live in {@link AraAgents} rather than as default
 * methods here, so every implementation only has to reason about the operations below.
 *
 * <p>An {@code AraAgent} encapsulates a single executable unit of intelligence.
 * It receives an {@link AgentTask}, processes it through its internal execution
 * strategy (ReAct, Plan-and-Execute, etc.), and produces an {@link AgentResponse}.
 *
 * <p>Implementations are expected to be thread-safe: each instance runs on
 * a dedicated Java 21 virtual thread, but the {@link #execute(AgentTask)} method
 * may be called from any thread that holds a reference to the agent.
 *
 * <p><strong>No annotations are used.</strong> Dependency injection is performed
 * explicitly at construction time via {@code AgentFactory}, keeping the wiring
 * fully visible and testable without any framework magic.
 *
 * <p>Session management ({@code terminate(SessionId)}, {@code invalidateSession},
 * {@code activeSessionCount()}) and hot reconfiguration ({@code reconfigure(AgentConfig)})
 * are <em>not</em> part of this contract — they used to be {@code default} methods here
 * (no-op / throwing for agents that don't support them), which forced every implementation
 * to either inherit a meaningless default or blow up at runtime with {@code
 * UnsupportedOperationException}. An agent that owns them (e.g. {@code AgentInstance},
 * {@code ContractEnforcingAgent}) declares {@code io.ara.runtime.agent.SessionScoped}
 * and/or {@code io.ara.runtime.agent.Reconfigurable} instead — the same pattern already
 * used for {@code SessionHistoryAware}/{@code RunStateAware}/{@code UserMemoryAware} — so
 * a caller checks {@code instanceof} rather than discovering support (or its absence) by
 * calling and seeing what happens. An agent like {@code GraphAgent} that manages neither
 * simply doesn't implement either interface, instead of silently inheriting four methods
 * that meant nothing for it.
 *
 * <p>Usage example (from {@code AgentFactory}):
 * <pre>{@code
 * AraAgent agent = factory.create(AgentConfig.defaults()
 *     .agentType("researcher")
 *     .systemPrompt("You are an expert researcher...")
 *     .build());
 *
 * AgentResponse response = agent.execute(AgentTask.of("Summarise the latest AI news"));
 * }</pre>
 */
public interface AraAgent {

    /**
     * Returns the unique identifier of this agent instance.
     *
     * @return the agent's {@link AgentId}; never {@code null}
     */
    AgentId agentId();

    /**
     * Returns the immutable configuration this agent was built from.
     *
     * @return the {@link AgentConfig}; never {@code null}
     */
    AgentConfig config();

    /**
     * Returns the current lifecycle state of this agent.
     *
     * <p>The state transitions are managed internally by the {@code AgentStateMachine}.
     * External callers should treat this as an observable snapshot — it may change
     * concurrently on the agent's virtual thread.
     *
     * @return the current {@link AgentState}; never {@code null}
     */
    AgentState currentState();

    /**
     * Submits a task for execution and blocks until the agent produces a response.
     *
     * <p>The method drives the full ReAct loop:
     * <ol>
     *   <li>Transitions the agent from {@code IDLE} to {@code PLANNING}</li>
     *   <li>Selects an execution strategy</li>
     *   <li>Runs the Perceive → Think → Act → Observe → Evaluate loop</li>
     *   <li>Returns an {@link AgentResponse} regardless of success or failure</li>
     * </ol>
     *
     * <p>This method never throws checked exceptions. All errors are captured
     * and returned as a {@link AgentResponse} with {@code finalState == FAILED}.
     *
     * @param task the task to execute; must not be {@code null}
     * @return the agent's response; never {@code null}
     * @throws IllegalStateException if the agent is not in {@link AgentState#IDLE}
     *                               when this method is called
     */
    AgentResponse execute(AgentTask task);

    /**
     * Terminates the agent, releasing all held resources.
     *
     * <p>If the agent is currently executing, it is interrupted and transitions
     * to {@link AgentState#FAILED} before being terminated. This method is
     * idempotent — calling it multiple times has no additional effect.
     */
    void terminate();
}
