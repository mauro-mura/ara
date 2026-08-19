package io.ara.core.agent;

import io.ara.core.media.MediaRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the one invariant this channel relaxes: blank input is legal exactly when the
 * task carries media, and never otherwise.
 */
class AgentTaskMediaTest {

    private static final MediaRef PDF =
            new MediaRef("digest-1", "application/pdf", "contract.pdf", 2048, null);

    @Test
    void media_only_task_is_valid() {
        AgentTask task = AgentTask.of("", List.of(PDF));
        assertEquals("", task.input());
        assertEquals(List.of(PDF), task.media());
    }

    @Test
    void blank_input_without_media_still_fails() {
        assertThrows(IllegalArgumentException.class, () -> AgentTask.of(""));
        assertThrows(IllegalArgumentException.class, () -> AgentTask.of("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> AgentTask.of("   ", List.of()));
    }

    @Test
    void text_only_task_has_empty_media() {
        assertTrue(AgentTask.of("hello").media().isEmpty());
    }

    @Test
    void media_list_is_defensively_copied_and_immutable() {
        List<MediaRef> mutable = new ArrayList<>(List.of(PDF));
        AgentTask task = AgentTask.of("look", mutable);

        mutable.clear();
        assertEquals(List.of(PDF), task.media(), "the task must not see the caller's later edits");
        assertThrows(UnsupportedOperationException.class, () -> task.media().add(PDF));
    }

    @Test
    void withInput_blanking_a_media_task_is_allowed() {
        assertDoesNotThrow(() -> AgentTask.of("look", List.of(PDF)).withInput(""));
    }

    @Test
    void withMedia_emptying_a_blank_input_task_is_rejected() {
        AgentTask mediaOnly = AgentTask.of("", List.of(PDF));
        assertThrows(IllegalArgumentException.class, () -> mediaOnly.withMedia(List.of()));
    }
}
