package io.ara.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunCheckpointTest {

    private static RunCheckpoint sample() {
        return new RunCheckpoint(
                SessionId.of("s1"), "task-1", "say hello", 3,
                List.of(LlmMessage.system("sys"), LlmMessage.user("hi"),
                        LlmMessage.assistantToolCall("c1", "delete-record", "{\"id\":7}", "thinking"),
                        LlmMessage.tool("c1", "delete-record", "ok")),
                List.of(ExecutionStep.thought("plan", 1),
                        ExecutionStep.toolCall("delete-record", "{\"id\":7}", 2)),
                120, 45, Money.of("0.0123", "EUR"));
    }

    @Test
    void jacksonRoundTrip_restoresEveryField() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunCheckpoint original = sample();

        RunCheckpoint restored = mapper.readValue(mapper.writeValueAsString(original), RunCheckpoint.class);

        assertEquals(original, restored);
    }

    @Test
    void lists_areDefensivelyCopied() {
        List<LlmMessage> memory = new ArrayList<>(List.of(LlmMessage.user("hi")));
        RunCheckpoint checkpoint = new RunCheckpoint(SessionId.of("s1"), "t", "in", 0, memory, null, 0, 0,
                Money.zero("EUR"));

        memory.add(LlmMessage.user("later"));

        assertEquals(1, checkpoint.workingMemory().size(), "later edits to the source list must not leak in");
        assertEquals(List.of(), checkpoint.steps(), "null steps become empty");
    }

    @Test
    void invalidArguments_areRejected() {
        SessionId session = SessionId.of("s1");
        Money zero = Money.zero("EUR");

        assertThrows(NullPointerException.class,
                () -> new RunCheckpoint(null, "t", "in", 0, null, null, 0, 0, zero));
        assertThrows(IllegalArgumentException.class,
                () -> new RunCheckpoint(session, " ", "in", 0, null, null, 0, 0, zero));
        assertThrows(IllegalArgumentException.class,
                () -> new RunCheckpoint(session, "t", "in", -1, null, null, 0, 0, zero));
        assertThrows(IllegalArgumentException.class,
                () -> new RunCheckpoint(session, "t", "in", 0, null, null, -1, 0, zero));
        assertThrows(NullPointerException.class,
                () -> new RunCheckpoint(session, "t", "in", 0, null, null, 0, 0, null));
        assertThrows(NullPointerException.class,
                () -> new RunCheckpoint(session, "t", null, 0, null, null, 0, 0, zero));
    }
}
