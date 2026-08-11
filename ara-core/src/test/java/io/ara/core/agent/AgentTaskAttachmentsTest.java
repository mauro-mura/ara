package io.ara.core.agent;

import io.ara.core.llm.LlmExecutionHints;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies attachment survival guarantees required by ADR-037/ADR-041 rev. 2: every
 * existing {@code with*} method on {@link AgentTask} must propagate {@link
 * AgentTask#runContext()} unchanged, since {@code ContractEnforcer} chains several of
 * them on the critical execution path before {@code AraAgent.execute()} is reached.
 */
class AgentTaskAttachmentsTest {

    private record Marker(String value) {}

    @Test
    void default_attachments_is_empty() {
        assertTrue(AgentTask.of("hi").runContext().opaque().isEmpty());
    }

    @Test
    void withAttachment_adds_and_reads_back_typed_value() {
        Marker marker = new Marker("secret");
        AgentTask task = AgentTask.of("hi").withAttachment("k", marker);
        assertSame(marker, task.runContext().opaque("k", Marker.class));
    }

    @Test
    void withAttachment_null_value_removes_key() {
        AgentTask task = AgentTask.of("hi")
                .withAttachment("k", new Marker("secret"))
                .withAttachment("k", null);
        assertNull(task.runContext().opaque("k", Marker.class));
        assertFalse(task.runContext().opaque().containsKey("k"));
    }

    @Test
    void attachment_missing_key_returns_null() {
        assertNull(AgentTask.of("hi").runContext().opaque("absent", Marker.class));
    }

    @Test
    void attachments_map_is_immutable() {
        AgentTask task = AgentTask.of("hi").withAttachment("k", new Marker("v"));
        assertThrows(UnsupportedOperationException.class,
                () -> task.runContext().opaque().put("other", new Marker("x")));
    }

    // ── Survival through every existing wither (ADR-037/ADR-041 binding requirement) ──

    @Test
    void attachment_survives_withContextEntry() {
        assertSurvives(t -> t.withContextEntry("k", "v"));
    }

    @Test
    void attachment_survives_withInput() {
        assertSurvives(t -> t.withInput("new input"));
    }

    @Test
    void attachment_survives_withHints() {
        assertSurvives(t -> t.withHints(LlmExecutionHints.empty()));
    }

    @Test
    void attachment_survives_withOutputSchema() {
        assertSurvives(t -> t.withOutputSchema("{\"type\":\"object\"}", true));
    }

    @Test
    void attachment_survives_withSessionId() {
        assertSurvives(t -> t.withSessionId(SessionId.of("s1")));
    }

    @Test
    void attachment_survives_withToolCallCallback() {
        assertSurvives(t -> t.withToolCallCallback(id -> { }));
    }

    @Test
    void attachment_survives_chain_of_withers_in_ContractEnforcer_order() {
        // Mirrors the sequence ContractEnforcer applies on the critical path:
        // withInput -> withHints (via withOutputSchema) -> withContextEntry
        Marker marker = new Marker("payload");
        AgentTask task = AgentTask.of("hi").withAttachment("securityContext", marker);

        AgentTask afterChain = task
                .withInput("shaped input")
                .withOutputSchema("{\"type\":\"object\"}", false)
                .withContextEntry("ara.system_prompt", "shaped prompt");

        assertSame(marker, afterChain.runContext().opaque("securityContext", Marker.class));
    }

    private static void assertSurvives(UnaryOperator<AgentTask> wither) {
        Marker marker = new Marker("payload");
        AgentTask task = AgentTask.of("hi", Map.of()).withAttachment("k", marker);
        AgentTask after = wither.apply(task);
        assertSame(marker, after.runContext().opaque("k", Marker.class),
                "attachment must survive " + wither);
    }
}
