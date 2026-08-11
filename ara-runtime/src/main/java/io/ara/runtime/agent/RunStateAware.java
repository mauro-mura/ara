package io.ara.runtime.agent;

import io.ara.core.agent.SessionId;

import java.util.Map;

/**
 * Implemented by anything that can report a session's shared {@code RunState} without
 * requiring the caller to know whether it's talking to a raw {@link AgentInstance} or a
 * decorator (e.g. {@code ContractEnforcingAgent}) wrapping one — same pattern as {@link
 * SessionHistoryAware}, for the same reason: {@code AgentFactory} registers the
 * decorator, not the raw instance, whenever a non-empty contract is supplied, so an
 * {@code instanceof AgentInstance} check at a call site would be a bug.
 */
public interface RunStateAware {

    /**
     * Returns a snapshot of {@code sessionId}'s shared state, or an empty map if no
     * session with that id currently exists (never used, or already evicted). Read-only:
     * never creates a session as a side effect.
     */
    Map<String, Object> sessionState(SessionId sessionId);
}
