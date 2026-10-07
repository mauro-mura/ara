package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model that answers with a native tool call and no text says nothing, and must not leave a
 * "thought" saying it. {@code THOUGHT} is what the model wrote as the step; a step with no text has
 * no thought. The tool call itself is still recorded, as its own {@code TOOL_CALL} step.
 */
class EmptyThoughtTest {

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success("echo", "ECHO"); }
    };

    private static final ToolRegistry TOOLS = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(ECHO); }
        @Override public Optional<AraTool> findById(String id) { return Optional.of(ECHO); }
        @Override public ToolResult execute(String id, String json) { return ECHO.execute(json); }
    };

    private static ExecutionResult run(LlmCompletion first) {
        ScriptedLlmClient llm = ScriptedLlmClient.script().then(first).thenFinalAnswer("done").build();
        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "go");
        AgentConfig config = AgentConfig.defaults().primaryLlm(LlmProfile.of("stub")).plannerStrategy("react").build();
        return new ReactStrategy().execute(AgentTask.of("go"), llm, memory, TOOLS, config);
    }

    private static List<ExecutionStep> thoughts(ExecutionResult result) {
        return result.steps().stream().filter(s -> s.type() == StepType.THOUGHT).toList();
    }

    @Test
    void aNativeToolCallWithNoText_leavesNoEmptyThought_andStillRecordsTheCall() {
        LlmCompletion silentCall = new LlmCompletion("", 10, 10, "tool_calls", null, null,
                List.of(new ToolCallEntry("c1", "echo", "{}")), false);

        ExecutionResult result = run(silentCall);

        assertTrue(result.isSuccess());
        assertTrue(thoughts(result).stream().noneMatch(t -> t.content() == null || t.content().isBlank()),
                "no thought without text: " + result.steps());
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.TOOL_CALL), "the call is recorded");
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.OBSERVATION), "and so is its result");
    }

    @Test
    void aToolCallWithAccompanyingText_keepsItsThought() {
        LlmCompletion talkingCall = new LlmCompletion("I will look that up.", 10, 10, "tool_calls", null, null,
                List.of(new ToolCallEntry("c1", "echo", "{}")), false);

        ExecutionResult result = run(talkingCall);

        assertEquals(List.of("I will look that up."), thoughts(result).stream().map(ExecutionStep::content)
                .filter(c -> c.contains("look")).toList());
    }

    @Test
    void aRunThatEndsOnAnAnswer_stillHasItsThoughtAndAnswer() {
        ExecutionResult result = run(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: x", 10, 10, "stop", null));

        assertFalse(result.steps().isEmpty());
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.FINAL_ANSWER));
    }
}
