package io.ara.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the {@link StepType} wire format down: it replaces a plain {@code String} field
 * that used to be serialised verbatim, so the enum's JSON shape must stay exactly
 * {@code "tool_call"} / {@code "thought"} / ... rather than the Java constant names
 * Jackson's default enum handling would otherwise produce ({@code "TOOL_CALL"}).
 */
class StepTypeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void wireValue_matchesTheOriginalStringLiterals() {
        assertEquals("thought",      StepType.THOUGHT.wireValue());
        assertEquals("tool_call",    StepType.TOOL_CALL.wireValue());
        assertEquals("observation",  StepType.OBSERVATION.wireValue());
        assertEquals("final_answer", StepType.FINAL_ANSWER.wireValue());
        assertEquals("speak",        StepType.SPEAK.wireValue());
        assertEquals("reflection",   StepType.REFLECTION.wireValue());
    }

    @Test
    void jacksonSerialisesToTheWireValue_notTheConstantName() throws Exception {
        assertEquals("\"tool_call\"", JSON.writeValueAsString(StepType.TOOL_CALL));
    }

    @Test
    void jacksonRoundTripsThroughAnExecutionStep() throws Exception {
        ExecutionStep step = ExecutionStep.toolCall("search", "{\"q\":\"x\"}", 3);

        String json = JSON.writeValueAsString(step);
        assertEquals(StepType.TOOL_CALL, StepType.fromWireValue("tool_call"));

        ExecutionStep roundTripped = JSON.readValue(json, ExecutionStep.class);
        assertEquals(step, roundTripped);
    }

    @Test
    void fromWireValue_rejectsAnUnknownToken() {
        assertThrows(IllegalArgumentException.class, () -> StepType.fromWireValue("bogus"));
    }

    @Test
    void factoriesProduceTheExpectedType() {
        assertEquals(StepType.THOUGHT,      ExecutionStep.thought("x", 1).type());
        assertEquals(StepType.TOOL_CALL,    ExecutionStep.toolCall("t", "{}", 1).type());
        assertEquals(StepType.OBSERVATION,  ExecutionStep.observation("x", 1).type());
        assertEquals(StepType.FINAL_ANSWER, ExecutionStep.finalAnswer("x", 1).type());
        assertEquals(StepType.SPEAK,        ExecutionStep.speak("x", 1).type());
        assertEquals(StepType.REFLECTION,   ExecutionStep.reflection("x", 1).type());
    }
}
