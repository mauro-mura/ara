package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: {@code PlanExecuteStrategy} used to return {@code ExecutionResult}s built
 * without a step trace and never called {@code AgentTask.notifyToolCall} — so {@code
 * AgentResponse.steps()} was always empty for {@code plan_execute} (despite {@link
 * ExecutionStep}'s contract that traces reach the caller) and no {@code tool_call} SSE
 * event ever fired, silently, only for this strategy.
 */
class PlanExecuteStrategyTraceTest {

    private static AraTool noopTool() {
        return new AraTool() {
            @Override public String toolId()         { return "noop"; }
            @Override public String description()    { return "no-op"; }
            @Override public String argumentSchema()  { return "{}"; }
            @Override public ToolResult execute(String argsJson) { return ToolResult.success("noop", "ok"); }
        };
    }

    private static ToolRegistry namedRegistry(AraTool... tools) {
        var map = new HashMap<String, AraTool>();
        for (var t : tools) map.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) {
                return ids.isEmpty() ? List.copyOf(map.values())
                        : ids.stream().map(map::get).filter(Objects::nonNull).toList();
            }
            @Override public Optional<AraTool> findById(String id) {
                return Optional.ofNullable(map.get(id));
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                var t = map.get(toolId);
                return t != null ? t.execute(argumentJson) : ToolResult.failure(toolId, "unknown tool");
            }
        };
    }

    private static InMemoryMemoryManager seeded(String systemPrompt, String userInput) {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("system", systemPrompt);
        m.appendToWorkingMemory("user", userInput);
        return m;
    }

    @Test
    void recordsFullTrace_andFiresToolCallCallback() {
        // Script: planning → one-step plan; step round 1 → tool call; step round 2 →
        // STEP_DONE; synthesis → final answer.
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(new LlmCompletion("1. Call the noop tool and report", 10, 10, "stop", null))
                .thenToolCall("noop", "{}")
                .then(new LlmCompletion("Tool reported ok. STEP_DONE", 10, 10, "stop", null))
                .then(new LlmCompletion("Final summary.", 10, 10, "stop", null))
                .build();

        List<String> notifiedTools = new ArrayList<>();
        AgentTask task = AgentTask.of("do the thing").withToolCallCallback(notifiedTools::add);

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("plan_execute")
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                task, llm, seeded("You are helpful.", "do the thing"),
                namedRegistry(noopTool()), config);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals("Final summary.", result.output());

        List<ExecutionStep> steps = result.steps();
        assertFalse(steps.isEmpty(), "plan_execute must produce an execution trace");
        assertEquals(StepType.THOUGHT, steps.get(0).type(), "first step is the plan itself");
        assertTrue(steps.stream().anyMatch(
                        s -> s.type() == StepType.TOOL_CALL && "noop".equals(s.toolId())),
                "the noop dispatch must appear as a tool_call step");
        assertTrue(steps.stream().anyMatch(s -> s.type() == StepType.OBSERVATION),
                "the tool result must appear as an observation step");
        assertEquals(StepType.FINAL_ANSWER, steps.get(steps.size() - 1).type(),
                "last step is the synthesised final answer");

        assertEquals(List.of("noop"), notifiedTools,
                "the tool_call SSE callback must fire once per dispatched tool");
    }

    @Test
    void failureResults_carryThePartialTrace() {
        // Planning succeeds, then the script is exhausted: the fallback completion keeps
        // answering FINAL_ANSWER text, so the single-step plan completes; force failure
        // instead via a tool-calling loop that exhausts maxIterations.
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(new LlmCompletion("1. Loop on the noop tool", 10, 10, "stop", null))
                .thenToolCall("noop", "{}")
                .thenToolCall("noop", "{}")
                .thenToolCall("noop", "{}")
                .build();

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("plan_execute")
                .maxIterations(3)   // plan (1) + two tool rounds (2,3) → exhausted mid-step
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("loop"), llm, seeded("You are helpful.", "loop"),
                namedRegistry(noopTool()), config);

        assertFalse(result.isSuccess());
        assertFalse(result.steps().isEmpty(),
                "a failed run must still expose the partial trace it accumulated");
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.TOOL_CALL),
                "the dispatched tool calls must be visible in the partial trace");
    }
}
