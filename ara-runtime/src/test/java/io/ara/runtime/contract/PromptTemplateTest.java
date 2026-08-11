package io.ara.runtime.contract;

import io.ara.core.agent.AgentInstanceContext;
import io.ara.core.agent.AgentTask;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.InstanceContextStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link PromptTemplate#withInstanceContext(AgentInstanceContext)} (ADR-036). */
class PromptTemplateTest {

    @Test
    void withInstanceContext_resolvesFromCtxWhenTaskContextAbsent() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("buddy"), Map.of("tenant_id", "jrc-ispra"));

        PromptTemplate template = PromptTemplate
                .withInstanceContext(store.forAgent(AgentId.of("buddy")))
                .delimiters("{{", "}}");

        String shaped = template.shape("Tenant: {{tenant_id}}", AgentTask.of("hi"));

        assertEquals("Tenant: jrc-ispra", shaped);
    }

    @Test
    void taskContext_takesPriorityOverInstanceContext() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("buddy"), Map.of("tenant_id", "jrc-ispra"));

        PromptTemplate template = PromptTemplate
                .withInstanceContext(store.forAgent(AgentId.of("buddy")))
                .delimiters("{{", "}}");

        AgentTask task = AgentTask.of("hi", Map.of("tenant_id", "override-from-task"));
        String shaped = template.shape("Tenant: {{tenant_id}}", task);

        assertEquals("Tenant: override-from-task", shaped);
    }

    @Test
    void live_updateAfterTemplateBuilt_reflectsOnNextShapeCall() {
        InstanceContextStore store = new InstanceContextStore();
        store.set(AgentId.of("buddy"), Map.of("tenant_id", "jrc-ispra"));

        // Template built ONCE, before the update — unlike withDefaults(Map), it must not
        // freeze the value at construction time.
        PromptTemplate template = PromptTemplate
                .withInstanceContext(store.forAgent(AgentId.of("buddy")))
                .delimiters("{{", "}}");

        assertEquals("Tenant: jrc-ispra", template.shape("Tenant: {{tenant_id}}", AgentTask.of("hi")));

        store.set(AgentId.of("buddy"), Map.of("tenant_id", "jrc-milano"));

        assertEquals("Tenant: jrc-milano", template.shape("Tenant: {{tenant_id}}", AgentTask.of("hi")),
                "the same PromptTemplate instance must reflect the store update without being rebuilt");
    }

    @Test
    void withDefaults_stillFreezesSnapshotAtConstructionTime() {
        // Regression: withDefaults(Map) must keep its existing frozen-snapshot behaviour
        // after the internal Map -> Function refactor.
        java.util.Map<String, String> mutable = new java.util.HashMap<>(Map.of("lang", "italiano"));
        PromptTemplate template = PromptTemplate.withDefaults(mutable);

        mutable.put("lang", "mutated-after-construction");

        String shaped = template.shape("Lang: {lang}", AgentTask.of("hi"));
        assertEquals("Lang: italiano", shaped, "withDefaults must copy the map, not reference it live");
    }

    @Test
    void unresolvedPlaceholder_leftUnchanged_whenNotStrict() {
        InstanceContextStore store = new InstanceContextStore();   // no params set for "buddy"
        PromptTemplate template = PromptTemplate
                .withInstanceContext(store.forAgent(AgentId.of("buddy")))
                .delimiters("{{", "}}");

        String shaped = template.shape("Tenant: {{tenant_id}}", AgentTask.of("hi"));
        assertEquals("Tenant: {{tenant_id}}", shaped);
    }

    @Test
    void strict_throwsOnUnresolvedInstanceContextPlaceholder() {
        InstanceContextStore store = new InstanceContextStore();
        PromptTemplate template = PromptTemplate
                .withInstanceContext(store.forAgent(AgentId.of("buddy")))
                .delimiters("{{", "}}")
                .strict();

        assertThrows(IllegalStateException.class,
                () -> template.shape("Tenant: {{tenant_id}}", AgentTask.of("hi")));
    }
}
