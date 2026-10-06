package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentEvent;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.StepType;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * For every strategy, the steps announced as events are the steps the strategy returns: one list
 * and one stream of events that cannot disagree about what was recorded. Where they do differ
 * (a strategy that retries and returns only its last attempt) the difference is asserted here,
 * so a strategy that starts dropping steps does not do it unnoticed.
 *
 * <p>The strategies are run directly, with an emitter attached by hand, which is what {@code
 * AgentInstance} does around them.
 */
class RunEventsStrategiesTest {

    private static AraTool tool(String id, boolean succeeds) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return id; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) {
                return succeeds ? ToolResult.success(id, "ok") : ToolResult.failure(id, "boom");
            }
        };
    }

    private static ToolRegistry registry(AraTool... tools) {
        var map = new HashMap<String, AraTool>();
        for (var t : tools) map.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) {
                return ids.isEmpty() ? List.copyOf(map.values())
                        : ids.stream().map(map::get).filter(Objects::nonNull).toList();
            }
            @Override public Optional<AraTool> findById(String id) { return Optional.ofNullable(map.get(id)); }
            @Override public ToolResult execute(String id, String json) {
                var t = map.get(id);
                return t != null ? t.execute(json) : ToolResult.failure(id, "unknown tool");
            }
        };
    }

    private static InMemoryMemoryManager seeded() {
        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "do it");
        return memory;
    }

    private static AgentTask observed(List<AgentEvent> sink) {
        AgentTask task = AgentTask.of("do it").withEventListener(sink::add);
        return RunEvents.forRun(task, "agent-x").attachTo(task);
    }

    private static List<ExecutionStep> stepsOf(List<AgentEvent> events) {
        return events.stream().filter(AgentEvent.StepRecorded.class::isInstance)
                .map(e -> ((AgentEvent.StepRecorded) e).step()).toList();
    }

    private static void assertStepEventsMatch(List<AgentEvent> events, ExecutionResult result, StepType expectedKind) {
        List<ExecutionStep> announced = stepsOf(events);
        assertEquals(result.steps(), announced, "events and the returned steps must be the same list");
        assertTrue(announced.stream().anyMatch(s -> s.type() == expectedKind),
                "the scenario must really exercise " + expectedKind + ": " + announced);
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i, events.get(i).seq(), "seq is contiguous from 0 within one run");
            assertEquals("agent-x", events.get(i).agentId());
        }
    }

    private static LlmCompletion text(String s) {
        return new LlmCompletion(s, 10, 10, "stop", null);
    }

    @Test
    void react_stepEventsMatchTheSteps() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("echo", "{}").thenFinalAnswer("done").build();
        AgentConfig config = AgentConfig.defaults().primaryLlm(LlmProfile.of("stub")).plannerStrategy("react").build();

        ExecutionResult result = new ReactStrategy().execute(observed(events), llm, seeded(),
                registry(tool("echo", true)), config);

        assertTrue(result.isSuccess());
        assertStepEventsMatch(events, result, StepType.OBSERVATION);
        assertEquals(StepType.FINAL_ANSWER, stepsOf(events).get(stepsOf(events).size() - 1).type());
    }

    @Test
    void planExecute_stepEventsMatchTheSteps() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(text("1. Call the noop tool and report"))
                .thenToolCall("noop", "{}")
                .then(text("Tool reported ok. STEP_DONE"))
                .then(text("Final summary."))
                .build();
        AgentConfig config = AgentConfig.defaults().primaryLlm(LlmProfile.of("stub")).plannerStrategy("plan_execute").build();

        ExecutionResult result = new PlanExecuteStrategy().execute(observed(events), llm, seeded(),
                registry(tool("noop", true)), config);

        assertTrue(result.isSuccess());
        assertStepEventsMatch(events, result, StepType.TOOL_CALL);
    }

    @Test
    void reflAct_stepEventsMatchTheSteps_reflectionIncluded() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")
                .then(text("A different approach: skip the tool."))
                .then(text("Action: FINAL_ANSWER\nAnswer: done without the tool"))
                .build();
        AgentConfig config = AgentConfig.defaults().primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(StrategyConfig.ReflAct.defaults()).build();

        ExecutionResult result = new ReflActStrategy().execute(observed(events), llm, seeded(),
                registry(tool("flaky", false)), config);

        assertTrue(result.isSuccess());
        assertStepEventsMatch(events, result, StepType.REFLECTION);
    }

    @Test
    void reSpAct_stepEventsMatchTheSteps_speakIncluded() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(text("Action: SPEAK\nMessage: Which city do you mean?")).build();
        AgentConfig config = AgentConfig.defaults().primaryLlm(LlmProfile.of("stub")).plannerStrategy("respact").build();

        ExecutionResult result = new ReSpActStrategy().execute(observed(events), llm, seeded(),
                ToolRegistry.empty(), config);

        assertTrue(result.isSuccess());
        assertStepEventsMatch(events, result, StepType.SPEAK);
    }

    /** Records one distinctive thought per attempt, through the same helper the real strategies use. */
    private static ExecutionStrategy attemptsThatRecord(List<ExecutionResult> outcomes, List<String> thoughts) {
        int[] attempt = {0};
        return new ExecutionStrategy() {
            @Override public String strategyName() { return "recording-delegate"; }
            @Override
            public ExecutionResult execute(AgentTask task, LlmClient llm, MemoryManager memory,
                                           ToolRegistry tools, AgentConfig config) {
                int n = attempt[0]++;
                List<ExecutionStep> steps = new ArrayList<>();
                // Iteration restarts at 1 in every attempt, exactly as a real delegate's does.
                RunEvents.record(task, steps, ExecutionStep.thought(thoughts.get(n), 1));
                ExecutionResult outcome = outcomes.get(n);
                return outcome.isSuccess()
                        ? ExecutionResult.success(outcome.output(), 1, 10, 10, steps)
                        : ExecutionResult.failure(outcome.failureReasonOpt().orElse("failed"), 1, 10, 10, steps);
            }
        };
    }

    @Test
    void reflexion_eventsShowEveryAttempt_whileTheResultKeepsOnlyTheLast() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ExecutionStrategy delegate = attemptsThatRecord(
                List.of(ExecutionResult.failure("first try failed", 1, 10),
                        ExecutionResult.success("second try worked", 1, 10)),
                List.of("thought of the discarded attempt", "thought of the kept attempt"));
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("Root cause: scope. Try narrower.").build();
        AgentConfig config = AgentConfig.defaults().primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(2, null, null)).build();

        ExecutionResult result = new ReflexionStrategy(delegate).execute(observed(events), llm, seeded(),
                ToolRegistry.empty(), config);

        assertTrue(result.isSuccess());
        List<String> announced = stepsOf(events).stream().map(ExecutionStep::content).toList();
        assertEquals(List.of("thought of the discarded attempt", "thought of the kept attempt"), announced,
                "a live observer sees the discarded attempt as well");
        assertEquals(List.of("thought of the kept attempt"),
                result.steps().stream().map(ExecutionStep::content).toList(),
                "the returned steps are the last attempt's only");
        assertNotEquals(result.steps(), stepsOf(events));
    }
}
