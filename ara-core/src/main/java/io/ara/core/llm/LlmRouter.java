package io.ara.core.llm;

import io.ara.core.llm.LlmConfig;

/**
 * Selects the appropriate {@link LlmClient} for a given agent LLM configuration
 * and call context, applying the configured {@link io.ara.core.agent.LlmSelectionPolicy}.
 *
 * <p>Replaces the direct {@code LlmClient} injection in {@code AgentFactory} /
 * {@code AgentInstance} (ADR-030). The default implementation in {@code ara-runtime}
 * is {@code DefaultLlmRouter}.
 */
public interface LlmRouter {

    /**
     * Selects and returns the {@link LlmClient} to use for the next LLM call.
     *
     * @param config      the agent's LLM configuration (profiles + policy)
     * @param callContext context for the current LLM call (task category, hints, etc.)
     * @return the selected client; never {@code null}
     * @throws IllegalStateException if no suitable client can be resolved
     */
    LlmClient select(LlmConfig config, LlmCallContext callContext);
}
