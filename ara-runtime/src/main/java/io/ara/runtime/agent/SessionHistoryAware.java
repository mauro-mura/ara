package io.ara.runtime.agent;

import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;

import java.util.List;

/**
 * Implemented by agents that record per-session conversation turns and can expose
 * them read-only.
 *
 * <p>Decorators over an {@link io.ara.core.agent.AraAgent} (e.g.
 * {@link ContractEnforcingAgent}) must implement this interface by delegating to the
 * wrapped agent, so that history stays reachable through
 * {@code AraRuntime.conversationHistory} no matter how many layers wrap the
 * underlying {@link AgentInstance}. Checking {@code instanceof AgentInstance} instead
 * is a bug: {@code AgentFactory} registers the decorator, never the raw instance,
 * whenever a non-empty contract is supplied.
 */
public interface SessionHistoryAware {

    /**
     * Returns the recorded conversation turns for {@code sessionId}, oldest-first —
     * empty if no session with that id currently exists (never used, or already
     * evicted by the session TTL sweeper). Read-only: never creates a session as a
     * side effect.
     */
    List<ConversationTurn> conversationHistory(SessionId sessionId);
}