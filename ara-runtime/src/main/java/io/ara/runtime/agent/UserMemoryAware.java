package io.ara.runtime.agent;

import io.ara.core.agent.UserId;

import java.util.Map;

/**
 * Implemented by anything that can report a user's cross-session memory (ADR-043 rev. 3)
 * without requiring the caller to know whether it's talking to a raw {@link AgentInstance}
 * or a decorator (e.g. {@code ContractEnforcingAgent}) wrapping one — same pattern as
 * {@link RunStateAware}/{@link SessionHistoryAware}, for the same reason: {@code
 * AgentFactory} registers the decorator, not the raw instance, whenever a non-empty
 * contract is supplied, so an {@code instanceof AgentInstance} check at a call site would
 * be a bug.
 */
public interface UserMemoryAware {

    /**
     * Returns a snapshot of {@code userId}'s cross-session memory, or an empty map if
     * nothing has ever been written for it. Read-only.
     */
    Map<String, Object> userMemory(UserId userId);
}
