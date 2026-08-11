package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers {@link AgentTask#speakCallback()}, {@link AgentTask#withSpeakCallback}, {@link AgentTask#notifySpeak}. */
class AgentTaskSpeakCallbackTest {

    @Test
    void default_speakCallback_isNull() {
        assertNull(AgentTask.of("hi").speakCallback());
    }

    @Test
    void notifySpeak_isANoOp_whenNoCallbackIsSet() {
        assertDoesNotThrow(() -> AgentTask.of("hi").notifySpeak("unheard"));
    }

    @Test
    void withSpeakCallback_setsTheCallback_andNotifySpeakInvokesIt() {
        List<String> received = new ArrayList<>();
        AgentTask task = AgentTask.of("hi").withSpeakCallback(received::add);

        task.notifySpeak("Which city do you mean?");

        assertEquals(List.of("Which city do you mean?"), received);
    }

    @Test
    void withSpeakCallback_isPropagatedByOtherWithMethods() {
        List<String> received = new ArrayList<>();
        AgentTask task = AgentTask.of("hi")
                .withSpeakCallback(received::add)
                .withInput("hello")
                .withContextEntry("k", "v")
                .withTaskId("custom-id");

        task.notifySpeak("still wired");

        assertEquals(List.of("still wired"), received,
                "withSpeakCallback must survive being chained through other with* copy methods");
    }
}
