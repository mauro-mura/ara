package io.ara.runtime.agent;

import io.ara.core.agent.AgentContract;

/**
 * Implemented by agents that can say which {@link AgentContract} they enforce — read-only, so
 * a caller outside the runtime (the gateway publishing an agent's input schema, a tool that
 * wants to know what an agent expects) can learn it without owning the agent's construction.
 *
 * <p>Decorators over an {@link io.ara.core.agent.AraAgent} must implement this by delegating,
 * for the reason {@link SessionHistoryAware} gives: {@code AgentFactory} registers the
 * outermost decorator, so an {@code instanceof ContractEnforcingAgent} check on a registered
 * agent fails as soon as another layer — tracing, say — wraps it.
 */
public interface ContractAware {

    /**
     * The contract this agent enforces; {@link AgentContract#empty()} when it enforces none,
     * never {@code null}.
     */
    AgentContract contract();
}
