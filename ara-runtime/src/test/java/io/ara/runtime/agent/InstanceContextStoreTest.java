package io.ara.runtime.agent;

import io.ara.core.agent.AgentInstanceContext;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for ADR-036's {@link InstanceContextStore}. */
class InstanceContextStoreTest {

    @Test
    void unknownAgent_returnsNullAndEmptyMap() {
        InstanceContextStore store = new InstanceContextStore();
        AgentInstanceContext ctx = store.forAgent(AgentId.of("ghost"));

        assertNull(ctx.get("api_key"));
        assertEquals("fallback", ctx.get("api_key", "fallback"));
        assertTrue(ctx.asMap().isEmpty());
    }

    @Test
    void set_thenForAgent_readsCurrentValues() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("buddy"), Map.of("api_key", "k1", "tenant_id", "jrc-ispra"));

        AgentInstanceContext ctx = store.forAgent(AgentId.of("buddy"));

        assertEquals("k1", ctx.get("api_key"));
        assertEquals("jrc-ispra", ctx.get("tenant_id"));
        assertEquals(Map.of("api_key", "k1", "tenant_id", "jrc-ispra"), ctx.asMap());
    }

    @Test
    void forAgent_viewIsLive_reflectsLaterUpdatesWithoutRebuilding() {
        InstanceContextStore store = new InstanceContextStore();
        AgentInstanceContext ctx = store.forAgent(AgentId.of("buddy"));   // obtained BEFORE any set()

        assertNull(ctx.get("api_key"));

        store.set(AgentId.of("buddy"), Map.of("api_key", "k1"));
        assertEquals("k1", ctx.get("api_key"));

        store.set(AgentId.of("buddy"), Map.of("api_key", "k2"));
        assertEquals("k2", ctx.get("api_key"), "the same ctx instance must see the updated value");
    }

    @Test
    void set_replacesEntireMap_doesNotMerge() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("buddy"), Map.of("api_key", "k1", "tenant_id", "jrc-ispra"));
        store.set(AgentId.of("buddy"), Map.of("api_key", "k2"));   // no tenant_id this time

        AgentInstanceContext ctx = store.forAgent(AgentId.of("buddy"));
        assertEquals("k2", ctx.get("api_key"));
        assertNull(ctx.get("tenant_id"), "set() must replace, not merge, the previous map");
    }

    @Test
    void clear_removesParametersForThatAgentOnly() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("buddy"), Map.of("api_key", "k1"));
        store.set(AgentId.of("other"), Map.of("api_key", "k2"));

        store.clear(AgentId.of("buddy"));

        assertNull(store.forAgent(AgentId.of("buddy")).get("api_key"));
        assertEquals("k2", store.forAgent(AgentId.of("other")).get("api_key"),
                "clearing one agent must not affect another");
    }

    @Test
    void attachments_typedReadAndLiveView() {
        InstanceContextStore store = new InstanceContextStore();
        AgentInstanceContext ctx = store.forAgent(AgentId.of("buddy"));   // obtained BEFORE any set

        assertNull(ctx.attachment("min_score", Double.class));
        assertTrue(ctx.attachments().isEmpty());

        store.setAttachments(AgentId.of("buddy"), Map.of(
                "min_score", 0.88,
                "max_results", 10,
                "synthesis_mode", true,
                "selectors", java.util.List.of("SUPPORT")));

        assertEquals(0.88, ctx.attachment("min_score", Double.class));
        assertEquals(10, ctx.attachment("max_results", Integer.class));
        assertEquals(true, ctx.attachment("synthesis_mode", Boolean.class));
        assertEquals(java.util.List.of("SUPPORT"), ctx.attachment("selectors", java.util.List.class));
    }

    @Test
    void attachments_wrongTypeThrowsClassCast() {
        InstanceContextStore store = new InstanceContextStore();
        store.setAttachments(AgentId.of("buddy"), Map.of("max_results", 10));
        AgentInstanceContext ctx = store.forAgent(AgentId.of("buddy"));

        assertThrows(ClassCastException.class, () -> ctx.attachment("max_results", String.class));
    }

    @Test
    void attachments_andStringParamsAreIndependentChannels() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("buddy"), Map.of("tenant_id", "jrc-ispra"));
        store.setAttachments(AgentId.of("buddy"), Map.of("min_score", 0.88));

        AgentInstanceContext ctx = store.forAgent(AgentId.of("buddy"));
        // String channel does not leak into attachments and vice-versa
        assertEquals("jrc-ispra", ctx.get("tenant_id"));
        assertNull(ctx.attachment("tenant_id", Object.class));
        assertEquals(0.88, ctx.attachment("min_score", Double.class));
        assertNull(ctx.get("min_score"));
    }

    @Test
    void clear_removesAttachmentsToo() {
        InstanceContextStore store = new InstanceContextStore();
        store.setAttachments(AgentId.of("buddy"), Map.of("min_score", 0.88));
        store.clear(AgentId.of("buddy"));

        assertNull(store.forAgent(AgentId.of("buddy")).attachment("min_score", Double.class));
    }

    @Test
    void differentAgents_areIsolated() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("agentA"), Map.of("k", "a"));
        store.set(AgentId.of("agentB"), Map.of("k", "b"));

        assertEquals("a", store.forAgent(AgentId.of("agentA")).get("k"));
        assertEquals("b", store.forAgent(AgentId.of("agentB")).get("k"));
    }
}
