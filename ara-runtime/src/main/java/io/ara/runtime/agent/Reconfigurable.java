package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;

/**
 * Implemented by agents that support hot reconfiguration (ADR-039) — replacing an
 * agent's {@link AgentConfig} in place, without terminating it or losing session state.
 *
 * <p>Decorators over an {@link io.ara.core.agent.AraAgent} (e.g. {@link
 * ContractEnforcingAgent}) must implement this interface by delegating to the wrapped
 * agent, so that reconfiguration stays reachable through {@code
 * AraRuntime.reconfigureAgent} no matter how many layers wrap the underlying {@link
 * AgentInstance} — same pattern as {@link SessionHistoryAware}/{@link RunStateAware}/
 * {@link UserMemoryAware}, for the same reason: {@code AgentFactory} registers the
 * decorator, not the raw instance, whenever a non-empty contract is supplied, so an
 * {@code instanceof AgentInstance} check at a call site would be a bug.
 *
 * <p>An agent that has no live, hot-swappable configuration (e.g. {@code GraphAgent}, a
 * {@code ParallelAgent} fan-out, or any pipeline step) simply does not implement this
 * interface — callers check {@code instanceof Reconfigurable} rather than calling and
 * discovering the absence of support via an {@code UnsupportedOperationException}.
 */
public interface Reconfigurable {

    /**
     * Applies {@code newConfig} in place. A task already in flight finishes with the
     * configuration it started with — same model, same parameters, same tools; only
     * sessions created after this call observe {@code newConfig}.
     *
     * @param newConfig the new configuration; {@code newConfig.agentId()} must equal
     *                  the agent's own id — changing the id is conceptually creating a
     *                  new agent, not reconfiguring this one
     * @throws IllegalArgumentException if {@code newConfig.agentId()} differs from the current id
     */
    void reconfigure(AgentConfig newConfig);
}
