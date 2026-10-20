package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.auth.ScopeSet;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.auth.ScopeVerifier;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ToolRegistry} decorator that filters by {@link AraTool#requiredScopes()} against
 * an {@link AgentView}'s {@link AgentView#effectiveScopes()} (ADR-033 Fase 3 §3.2).
 *
 * <p><b>Deviation from the ADR-033 implementation plan</b>: the plan describes this
 * filtering as an update to {@code DelegatingToolRegistry}. That class already exists in
 * this codebase with an unrelated responsibility — injecting {@link AgentDelegationTool}
 * into a registry, nothing to do with scopes — so bolting scope-filtering onto it would
 * conflate two independent concerns into one decorator. This is a new, separate decorator
 * instead; the two compose freely, in either order.
 *
 * <p>Two enforcement points, matching {@link LocalMessageBus}'s own split between
 * discovery and dispatch:
 * <ul>
 *   <li>{@link #resolveEnabled} silently drops a tool the caller's {@code effectiveScopes}
 *       don't satisfy — this is the catalog an LLM sees, so an unauthorized tool should
 *       never appear as something to attempt, not surface as a confusing runtime error.</li>
 *   <li>{@link #execute(String, String)}/{@link #execute(String, String, AgentTask)} check
 *       again and throw {@link io.ara.core.auth.AuthorizationException} — defense in depth
 *       for a call that reaches {@code execute} without going through {@link
 *       #resolveEnabled} first (nothing in the {@link ToolRegistry} contract requires it).</li>
 * </ul>
 *
 * <p>{@link #findById}/{@link #all()} are pass-through, deliberately unfiltered: they are
 * used for tool <em>definition</em> lookup (schema, description), not authorization to
 * call one — the same reasoning {@code AgentView.all()} uses for agent discovery.
 */
public final class ScopeFilteringToolRegistry implements ToolRegistry {

    private final ToolRegistry inner;
    private final AgentView    agentView;

    public ScopeFilteringToolRegistry(ToolRegistry inner, AgentView agentView) {
        this.inner     = Objects.requireNonNull(inner,     "inner must not be null");
        this.agentView = Objects.requireNonNull(agentView, "agentView must not be null");
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        ScopeSet effective = agentView.effectiveScopes();
        return inner.resolveEnabled(enabledToolIds).stream()
                .filter(t -> effective.grants(ScopeSet.of(t.requiredScopes())))
                .toList();
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        return inner.findById(toolId);
    }

    @Override
    public List<AraTool> all() {
        return inner.all();
    }

    /**
     * Forwarded, like {@link #findById}: the tool's classification lives on the registry this
     * one wraps, and {@code ApprovalToolRegistry} reads it through the whole chain to decide
     * whether a call is gated (ADR-0067 D6). Not forwarding it silently turns the per-tool
     * gate off for every agent built on top of this decorator.
     */
    @Override
    public Optional<ToolSpec> specFor(String toolId) {
        return inner.specFor(toolId);
    }

    /** Forwarded for the same reason as {@link #specFor}; see {@link ToolRegistry#approvalRequestFor}. */
    @Override
    public Optional<ApprovalRequest> approvalRequestFor(String toolId, String argumentJson, AgentTask task) {
        return inner.approvalRequestFor(toolId, argumentJson, task);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        checkAuthorized(toolId);
        return inner.execute(toolId, argumentJson);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        checkAuthorized(toolId);
        return inner.execute(toolId, argumentJson, task);
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return inner.wrapForPropagation(task);
    }

    private void checkAuthorized(String toolId) {
        inner.findById(toolId).ifPresent(tool -> ScopeVerifier.checkTool(tool, agentView.effectiveScopes()));
    }
}
