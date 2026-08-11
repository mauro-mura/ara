package io.ara.core.agent;

import java.util.Map;

/**
 * Live, per-agent key-value context (ADR-036) — readable by {@link
 * io.ara.core.agent.processor.PromptShaper}s and by {@link io.ara.core.tool.AraTool}
 * implementations alike, but never exposed to the LLM: no channel carries it into the
 * prompt text or a tool's JSON argument schema unless a shaper explicitly injects it.
 *
 * <p>"Live" means no value is frozen at construction time. Every read ({@link #get}/
 * {@link #asMap}) reflects the current state of the backing store at the moment of the
 * call — not a snapshot taken when the agent was created. Values can be updated at any
 * time via the backing store without recreating the agent, its {@code AgentContract},
 * or its {@code ToolRegistry}.
 *
 * <p>Typical use: a per-agent API key or tenant id, injected into a tool's constructor
 * (so it is never part of the LLM-controlled argument schema) and/or resolved into
 * system-prompt placeholders by a {@code PromptShaper} — both reading from the same
 * instance so the data has a single source of truth.
 */
public interface AgentInstanceContext {

    /**
     * @param key the parameter name
     * @return the current value, or {@code null} if absent
     */
    String get(String key);

    /**
     * @param key      the parameter name
     * @param fallback returned when {@code key} is absent
     * @return the current value, or {@code fallback} if absent
     */
    default String get(String key, String fallback) {
        String v = get(key);
        return v != null ? v : fallback;
    }

    /** @return an immutable snapshot of all current parameters for this agent */
    Map<String, String> asMap();

    /**
     * Typed, per-agent attachment — the internal (never-LLM-visible) counterpart of the
     * String parameters above. Where {@link #get}/{@link #asMap} hold String values that a
     * {@code PromptShaper} may resolve into the prompt, attachments hold arbitrary typed
     * objects meant exclusively for {@link io.ara.core.tool.AraTool} implementations
     * (config scalars, typed collections, credentials as opaque objects, ...). No channel
     * carries them into the prompt text or a tool's JSON argument schema.
     *
     * <p>This is the per-agent axis of the context model: (per-task vs per-agent) ×
     * (String/LLM-visible vs Object/internal). {@code RunContext.opaque()} (via {@code
     * AgentTask.runContext()}) is the per-task counterpart. The two axes are kept
     * strictly separate: a delegated sub-task carries the caller's {@code RunContext}
     * forward, but never this per-agent store — each agent in a delegation chain reads
     * its own instance parameters from its own store, regardless of who invoked it.
     * Letting per-agent config leak into the propagated per-task state would mean a
     * delegate silently inheriting the caller's tenant, API key, or other instance
     * configuration instead of its own.
     *
     * @param key  the attachment name
     * @param type the expected runtime type
     * @return the attachment cast to {@code type}, or {@code null} if absent
     * @throws ClassCastException if the stored value is not assignable to {@code type}
     */
    default <T> T attachment(String key, Class<T> type) {
        Object v = attachments().get(key);
        return v == null ? null : type.cast(v);
    }

    /** @return an immutable snapshot of all current typed attachments for this agent */
    default Map<String, Object> attachments() {
        return Map.of();
    }
}
