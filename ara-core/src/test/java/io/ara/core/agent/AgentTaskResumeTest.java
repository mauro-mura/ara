package io.ara.core.agent;

import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskResumeTest {

    private static RunCheckpoint checkpoint(String input) {
        return new RunCheckpoint(SessionId.of("s1"), "task-1", input, 2, null, null, 5, 6, Money.zero("EUR"));
    }

    @Test
    void resuming_takesSessionTaskIdAndInputFromTheCheckpoint() {
        RunCheckpoint checkpoint = checkpoint("say hello");

        AgentTask task = AgentTask.resuming(checkpoint);

        assertEquals("task-1", task.taskId());
        assertEquals(SessionId.of("s1"), task.sessionId());
        assertEquals("say hello", task.input());
        assertEquals(checkpoint, task.resumeCheckpoint().orElseThrow());
    }

    @Test
    void resuming_aCheckpointWithBlankInput_stillBuildsATask() {
        assertTrue(AgentTask.resuming(checkpoint("")).resumeCheckpoint().isPresent(),
                "a task made only of attachments has blank text but must still be resumable");
    }

    @Test
    void aTaskThatIsNotResuming_hasNoCheckpoint() {
        assertTrue(AgentTask.of("hi").resumeCheckpoint().isEmpty());
    }

    @Test
    void aCheckpoint_isHonouredOnlyByTheTaskItWasSavedFor() {
        // The opaque channel is copied into delegated tasks; a delegate has its own task id
        // and must start fresh rather than replay its caller's run.
        AgentTask delegate = AgentTask.resuming(checkpoint("say hello")).withTaskId("delegate-task");

        assertTrue(delegate.resumeCheckpoint().isEmpty());
    }
}
