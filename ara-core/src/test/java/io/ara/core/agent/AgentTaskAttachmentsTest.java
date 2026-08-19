package io.ara.core.agent;

import io.ara.core.llm.LlmExecutionHints;
import io.ara.core.media.MediaRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies attachment survival guarantees required by ADR-037/ADR-041 rev. 2: every
 * existing {@code with*} method on {@link AgentTask} must propagate {@link
 * AgentTask#runContext()} unchanged, since {@code ContractEnforcer} chains several of
 * them on the critical execution path before {@code AraAgent.execute()} is reached.
 *
 * <p>{@link AgentTask#media()} carries the same guarantee for the same reason, and is
 * checked by the same withers: a task whose PDF is dropped halfway down the enforcement
 * chain reaches the model as a question about a document it was never shown.
 */
class AgentTaskAttachmentsTest {

    private record Marker(String value) {}

    private static final MediaRef PDF =
            new MediaRef("digest-1", "application/pdf", "contract.pdf", 2048, null);

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

    // ── Media survival through every wither (same binding requirement) ────────────

    @Test
    void media_survives_withContextEntry() {
        assertMediaSurvives(t -> t.withContextEntry("k", "v"));
    }

    @Test
    void media_survives_withInput() {
        assertMediaSurvives(t -> t.withInput("new input"));
    }

    @Test
    void media_survives_withHints() {
        assertMediaSurvives(t -> t.withHints(LlmExecutionHints.empty()));
    }

    @Test
    void media_survives_withOutputSchema() {
        assertMediaSurvives(t -> t.withOutputSchema("{\"type\":\"object\"}", true));
    }

    @Test
    void media_survives_withSessionId() {
        assertMediaSurvives(t -> t.withSessionId(SessionId.of("s1")));
    }

    @Test
    void media_survives_withUserId() {
        assertMediaSurvives(t -> t.withUserId(UserId.of("u1")));
    }

    @Test
    void media_survives_withTaskId() {
        assertMediaSurvives(t -> t.withTaskId("other-id"));
    }

    @Test
    void media_survives_withToolCallCallback() {
        assertMediaSurvives(t -> t.withToolCallCallback(id -> { }));
    }

    @Test
    void media_survives_withSpeakCallback() {
        assertMediaSurvives(t -> t.withSpeakCallback(msg -> { }));
    }

    @Test
    void media_survives_withRunContext() {
        assertMediaSurvives(t -> t.withRunContext(RunContext.empty()));
    }

    @Test
    void media_survives_withAttachment() {
        assertMediaSurvives(t -> t.withAttachment("k", new Marker("v")));
    }

    @Test
    void media_survives_chain_of_withers_in_ContractEnforcer_order() {
        AgentTask afterChain = AgentTask.of("read this", List.of(PDF))
                .withInput("shaped input")
                .withOutputSchema("{\"type\":\"object\"}", false)
                .withContextEntry("ara.system_prompt", "shaped prompt");

        assertEquals(List.of(PDF), afterChain.media());
    }

    @Test
    void withMedia_replaces_the_list() {
        MediaRef image = new MediaRef("digest-2", "image/png", "scan.png", 512, null);
        AgentTask task = AgentTask.of("look", List.of(PDF)).withMedia(List.of(image));
        assertEquals(List.of(image), task.media());
    }

    private static void assertSurvives(UnaryOperator<AgentTask> wither) {
        Marker marker = new Marker("payload");
        AgentTask task = AgentTask.of("hi", Map.of()).withAttachment("k", marker);
        AgentTask after = wither.apply(task);
        assertSame(marker, after.runContext().opaque("k", Marker.class),
                "attachment must survive " + wither);
    }

    private static void assertMediaSurvives(UnaryOperator<AgentTask> wither) {
        AgentTask after = wither.apply(AgentTask.of("hi", List.of(PDF)));
        assertEquals(List.of(PDF), after.media(), "media must survive " + wither);
    }
}
