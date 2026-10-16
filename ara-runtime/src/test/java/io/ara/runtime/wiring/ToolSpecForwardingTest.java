package io.ara.runtime.wiring;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.Reversibility;
import io.ara.core.tool.SideEffects;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.bus.DelegatingToolRegistry;
import io.ara.runtime.bus.LocalMessageBus;
import io.ara.runtime.bus.ScopeFilteringToolRegistry;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.interceptor.InterceptingToolRegistry;
import io.ara.runtime.telemetry.TelemetryToolRegistry;
import io.ara.core.telemetry.AraTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link ToolRegistry} decorator the runtime puts in an agent's chain must forward
 * {@link ToolRegistry#specFor}: the per-tool approval gate (ADR-0067 D6) reads a tool's
 * classification through the whole chain, and a single decorator that drops it turns the gate
 * off for every agent built on it — silently, since an empty spec reads as "no gate needed".
 */
class ToolSpecForwardingTest {

    private static final ToolSpec HIGH_IMPACT = ToolSpec.builtin("pay", SideEffects.EXTERNAL_WRITE,
            new Reversibility.IrreversibleHighImpact());

    private static AraTool tool(String id) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return id; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(id, "ok"); }
        };
    }

    /** Knows {@code pay} and classifies it as irreversible, high impact. */
    private static final ToolRegistry CLASSIFIED = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool("pay")); }
        @Override public Optional<AraTool> findById(String id) {
            return "pay".equals(id) ? Optional.of(tool("pay")) : Optional.empty();
        }
        @Override public Optional<ToolSpec> specFor(String id) {
            return "pay".equals(id) ? Optional.of(HIGH_IMPACT) : Optional.empty();
        }
        @Override public ToolResult execute(String id, String json) { return ToolResult.success(id, "ok"); }
    };

    private static final AgentView NO_SCOPES = new AgentView() {
        @Override public Optional<io.ara.core.agent.AraAgent> findById(AgentId id) { return Optional.empty(); }
        @Override public Collection<io.ara.core.agent.AraAgent> all() { return List.of(); }
        @Override public String catalog(String contextInput) { return ""; }
        @Override public ScopeSet effectiveScopes() { return ScopeSet.EMPTY; }
    };

    private static void assertForwards(ToolRegistry decorator) {
        assertEquals(Optional.of(HIGH_IMPACT), decorator.specFor("pay"),
                decorator.getClass().getSimpleName() + " must forward specFor");
        assertTrue(decorator.specFor("unknown").isEmpty());
    }

    // ── approvalRequestFor (ADR-048) ──────────────────────────────────────────

    /** A gate-bearing registry: asks for approval of {@code pay}, and of nothing else. */
    private static final ToolRegistry GATED = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool("pay")); }
        @Override public Optional<AraTool> findById(String id) {
            return "pay".equals(id) ? Optional.of(tool("pay")) : Optional.empty();
        }
        @Override public Optional<ApprovalRequest> approvalRequestFor(String id, String json, AgentTask task) {
            return "pay".equals(id)
                    ? Optional.of(ApprovalRequest.of("agent", id, json, Duration.ofMinutes(1)))
                    : Optional.empty();
        }
        @Override public ToolResult execute(String id, String json) { return ToolResult.success(id, "ok"); }
    };

    private static void assertForwardsApproval(ToolRegistry decorator) {
        assertTrue(decorator.approvalRequestFor("pay", "{}", null).isPresent(),
                decorator.getClass().getSimpleName() + " must forward approvalRequestFor");
        assertTrue(decorator.approvalRequestFor("other", "{}", null).isEmpty());
    }

    @Test
    void everyDecorator_forwardsApprovalRequestFor() {
        assertForwardsApproval(new TelemetryToolRegistry(GATED, AraTelemetry.noop()));
        assertForwardsApproval(new InterceptingToolRegistry(GATED, new AgentInterceptorChain(List.of()), () -> null));
        assertForwardsApproval(new ScopeFilteringToolRegistry(GATED, NO_SCOPES));
        assertForwardsApproval(new DelegatingToolRegistry(GATED, new LocalMessageBus(new AgentRegistry()), "self"));
        assertForwardsApproval(new CompositeToolRegistry(List.of(tool("search")), GATED));
        assertForwardsApproval(new AggregatingToolRegistry(Map.of("a", ToolRegistry.empty(), "b", GATED)));
    }

    @Test
    void delegateTask_andShadowingMcpTools_areNotGatedByTheInnerRegistry() {
        ToolRegistry delegating = new DelegatingToolRegistry(GATED, new LocalMessageBus(new AgentRegistry()), "self");
        ToolRegistry shadowed = new CompositeToolRegistry(List.of(tool("pay")), GATED);

        assertTrue(delegating.approvalRequestFor("delegate_task", "{}", null).isEmpty());
        assertTrue(shadowed.approvalRequestFor("pay", "{}", null).isEmpty(),
                "findById returns the MCP tool, so the base tool's gate must not apply to it");
    }

    @Test
    void telemetry_forwards() {
        assertForwards(new TelemetryToolRegistry(CLASSIFIED, AraTelemetry.noop()));
    }

    @Test
    void intercepting_forwards() {
        assertForwards(new InterceptingToolRegistry(CLASSIFIED, new AgentInterceptorChain(List.of()), () -> null));
    }

    @Test
    void scopeFiltering_forwards() {
        assertForwards(new ScopeFilteringToolRegistry(CLASSIFIED, NO_SCOPES));
    }

    @Test
    void delegating_forwards_andLeavesDelegateTaskUnclassified() {
        ToolRegistry delegating = new DelegatingToolRegistry(CLASSIFIED, new LocalMessageBus(new AgentRegistry()), "self");

        assertForwards(delegating);
        assertTrue(delegating.specFor("delegate_task").isEmpty(),
                "delegate_task is gated by the delegate's own tools, not classified here");
    }

    @Test
    void composite_forwardsForBaseTools_andLeavesMcpToolsUnclassified() {
        ToolRegistry composite = new CompositeToolRegistry(List.of(tool("search")), CLASSIFIED);

        assertForwards(composite);
        assertTrue(composite.specFor("search").isEmpty(), "an MCP tool carries no classification");
    }

    @Test
    void composite_anMcpToolShadowingABaseTool_doesNotInheritItsSpec() {
        ToolRegistry composite = new CompositeToolRegistry(List.of(tool("pay")), CLASSIFIED);

        assertTrue(composite.specFor("pay").isEmpty(),
                "findById returns the MCP tool, so the base tool's classification must not apply to it");
    }

    @Test
    void aggregating_takesTheSpecFromTheRegistryThatKnowsTheTool() {
        ToolRegistry aggregating = new AggregatingToolRegistry(Map.of("agent-a", ToolRegistry.empty(), "agent-b", CLASSIFIED));

        assertForwards(aggregating);
    }
}
