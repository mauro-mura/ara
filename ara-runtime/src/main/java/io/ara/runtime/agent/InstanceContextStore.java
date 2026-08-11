package io.ara.runtime.agent;

import io.ara.core.agent.AgentInstanceContext;
import io.ara.core.common.AgentId;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central, shared store of {@link AgentInstanceContext} data, keyed by {@link AgentId}
 * (ADR-036). One store is shared across every agent created by the same {@link
 * io.ara.runtime.AraRuntime}; each agent only ever sees its own slice via {@link
 * #forAgent(AgentId)}.
 *
 * <p>Thread-safe by construction: backed by a {@link ConcurrentHashMap}, safe for
 * concurrent reads and writes from arbitrary threads — task execution threads reading
 * via {@link #forAgent}, and, concurrently, an external thread (e.g. an admin endpoint)
 * updating a parameter via {@link #set}.
 *
 * <p>{@link #set} replaces the entire parameter map for that agent — it does not merge.
 * A single-parameter update requires the caller to recompose the full map. The same
 * applies to {@link #setAttachments}.
 *
 * <p>Two parallel per-agent maps are held: the String {@code params} (LLM-visible, resolved
 * by {@code PromptShaper}s) and the typed {@code attachments} (internal, tool-only). They
 * are independent — updating one never touches the other.
 */
public final class InstanceContextStore {

    private final ConcurrentMap<String, Map<String, String>> byAgent = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, Object>> attachmentsByAgent = new ConcurrentHashMap<>();

    /** Replaces the entire parameter map for {@code agentId}. Does not merge with the previous value. */
    public void set(AgentId agentId, Map<String, String> params) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(params, "params must not be null");
        byAgent.put(agentId.value(), Map.copyOf(params));
    }

    /**
     * Replaces the entire typed-attachment map for {@code agentId} — the internal,
     * never-LLM-visible counterpart of {@link #set}. Does not merge with the previous value.
     * Values may be any object; {@code null} values are not permitted (use an empty map to clear).
     */
    public void setAttachments(AgentId agentId, Map<String, Object> attachments) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(attachments, "attachments must not be null");
        attachmentsByAgent.put(agentId.value(), Map.copyOf(attachments));
    }

    /** Removes all parameters and attachments for {@code agentId}. Call when the agent is destroyed to avoid a leak. */
    public void clear(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        byAgent.remove(agentId.value());
        attachmentsByAgent.remove(agentId.value());
    }

    /**
     * Returns a live view bound to {@code agentId}. The returned instance never freezes a
     * snapshot — every {@link AgentInstanceContext#get}/{@link AgentInstanceContext#asMap}
     * call reads the current backing map at that moment.
     */
    public AgentInstanceContext forAgent(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        String id = agentId.value();
        return new AgentInstanceContext() {
            @Override
            public String get(String key) {
                return byAgent.getOrDefault(id, Map.of()).get(key);
            }

            @Override
            public Map<String, String> asMap() {
                return byAgent.getOrDefault(id, Map.of());
            }

            @Override
            public Map<String, Object> attachments() {
                return attachmentsByAgent.getOrDefault(id, Map.of());
            }
        };
    }
}
