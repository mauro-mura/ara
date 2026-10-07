package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentEvent;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's reasoning becomes a {@code REASONING} step when the provider returned it and the
 * agent asked for it, in the strategies that record a model call, ahead of the text it led to;
 * never otherwise.
 */
class ReasoningStepsTest {

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

    private static AgentConfig.Builder config(Boolean returnReasoning) {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").returnReasoning(returnReasoning).build());
    }

    private static InMemoryMemoryManager memory() {
        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "go");
        return memory;
    }

    private static LlmCompletion withReasoning(String text, String finishReason, String reasoning) {
        return new LlmCompletion(text, 10, 10, finishReason, null, null, List.of(), false, reasoning);
    }

    private static List<StepType> types(ExecutionResult result) {
        return result.steps().stream().map(ExecutionStep::type).toList();
    }

    // ── react ──────────────────────────────────────────────────────────────────────────

    @Test
    void react_recordsTheReasoningFirst_whenAskedFor() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(withReasoning("Action: FINAL_ANSWER\nAnswer: 42", "stop", "I weighed it up."))
                .build();

        ExecutionResult result = new ReactStrategy().execute(AgentTask.of("go"), llm, memory(), TOOLS,
                config(true).plannerStrategy("react").build());

        assertEquals(StepType.REASONING, result.steps().get(0).type(), "reasoning comes before the text it led to");
        assertEquals("I weighed it up.", result.steps().get(0).content());
        assertTrue(types(result).contains(StepType.THOUGHT));
        assertTrue(types(result).contains(StepType.FINAL_ANSWER));
    }

    @Test
    void react_neverRecordsIt_unlessTheAgentAsked() {
        for (Boolean off : new Boolean[] {null, false}) {
            ScriptedLlmClient llm = ScriptedLlmClient.script()
                    .then(withReasoning("Action: FINAL_ANSWER\nAnswer: 42", "stop", "private reasoning"))
                    .build();

            ExecutionResult result = new ReactStrategy().execute(AgentTask.of("go"), llm, memory(), TOOLS,
                    config(off).plannerStrategy("react").build());

            assertFalse(types(result).contains(StepType.REASONING), "returnReasoning=" + off + ": " + result.steps());
        }
    }

    @Test
    void react_aCompletionWithoutReasoning_addsNoStep() {
        ScriptedLlmClient llm = ScriptedLlmClient.script().thenFinalAnswer("42").build();

        ExecutionResult result = new ReactStrategy().execute(AgentTask.of("go"), llm, memory(), TOOLS,
                config(true).plannerStrategy("react").build());

        assertFalse(types(result).contains(StepType.REASONING));
    }

    @Test
    void react_aSilentToolCall_withReasoning_keepsTheReasoning_andNoEmptyThought() {
        LlmCompletion silentCall = new LlmCompletion("", 10, 10, "tool_calls", null, null,
                List.of(new ToolCallEntry("c1", "echo", "{}")), false, "I should use the tool.");
        ScriptedLlmClient llm = ScriptedLlmClient.script().then(silentCall).thenFinalAnswer("done").build();

        ExecutionResult result = new ReactStrategy().execute(AgentTask.of("go"), llm, memory(), TOOLS,
                config(true).plannerStrategy("react").build());

        List<StepType> types = types(result);
        assertEquals(StepType.REASONING, types.get(0));
        assertEquals(StepType.TOOL_CALL, types.get(1), "a call with no text has a reasoning and a call, no blank thought");
    }

    @Test
    void react_theReasoningIsAnEvent_likeAnyStep() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentTask task = AgentTask.of("go").withEventListener(events::add);
        task = RunEvents.forRun(task, "a").attachTo(task);
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(withReasoning("Action: FINAL_ANSWER\nAnswer: 42", "stop", "because")).build();

        new ReactStrategy().execute(task, llm, memory(), TOOLS, config(true).plannerStrategy("react").build());

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StepRecorded s
                && s.step().type() == StepType.REASONING && "because".equals(s.step().content())));
    }

    // ── plan_execute ───────────────────────────────────────────────────────────────────

    @Test
    void planExecute_recordsThePlanningReasoning_beforeThePlan() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(withReasoning("1. Call the echo tool and report", "stop", "planning thoughts"))
                .thenToolCall("echo", "{}")
                .then(new LlmCompletion("Tool reported ok. STEP_DONE", 10, 10, "stop", null))
                .then(new LlmCompletion("Final summary.", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(AgentTask.of("go"), llm, memory(), TOOLS,
                config(true).plannerStrategy("plan_execute").build());

        assertEquals(StepType.REASONING, result.steps().get(0).type());
        assertEquals("planning thoughts", result.steps().get(0).content());
        assertEquals(StepType.THOUGHT, result.steps().get(1).type(), "the plan follows its reasoning");
    }

    // ── reflact ────────────────────────────────────────────────────────────────────────

    @Test
    void reflAct_recordsTheReflectionCallsReasoning_beforeTheReflection() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")
                .then(withReasoning("A different approach: skip the tool.", "stop", "why it failed"))
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done without the tool", 10, 10, "stop", null))
                .build();
        ToolRegistry failing = new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(); }
            @Override public Optional<AraTool> findById(String id) {
                return Optional.of(new AraTool() {
                    @Override public String toolId() { return "flaky"; }
                    @Override public String description() { return "flaky"; }
                    @Override public String argumentSchema() { return "{}"; }
                    @Override public ToolResult execute(String argumentJson) { return ToolResult.failure("flaky", "boom"); }
                });
            }
            @Override public ToolResult execute(String id, String json) { return ToolResult.failure(id, "boom"); }
        };
        AgentConfig config = config(true).strategyConfig(StrategyConfig.ReflAct.defaults()).build();

        ExecutionResult result = new ReflActStrategy().execute(AgentTask.of("go"), llm, memory(), failing, config);

        List<StepType> types = types(result);
        int reflection = types.indexOf(StepType.REFLECTION);
        assertTrue(reflection > 0 && types.get(reflection - 1) == StepType.REASONING,
                "the reflection call's reasoning sits right before the reflection: " + types);
    }

    // ── streaming ──────────────────────────────────────────────────────────────────────

    /** A client that streams text tokens and hands the terminal completion, with reasoning, to the sink. */
    private static final class StreamingWithReasoning implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            throw new AssertionError("the blocking call must not be needed");
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                boolean done;
                @Override public void request(long n) {
                    if (done) return;
                    done = true;
                    if (context.hasCompletionSink()) {
                        context.completionSink().accept(
                                new LlmCompletion("Hello", 7, 3, "stop", null, null, List.of(), false, "streamed reasoning"));
                    }
                    subscriber.onNext("Hel");
                    subscriber.onNext("lo");
                    subscriber.onComplete();
                }
                @Override public void cancel() { }
            });
        }

        @Override public String providerId() { return "streaming-with-reasoning"; }
    }

    @Test
    void streaming_takesTheReasoningFromTheSink_alsoWhenThereIsText() throws Exception {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").streamingEnabled(true).returnReasoning(true).build())
                .executionTimeout(Duration.ofSeconds(5)).build();
        AgentTask task = AgentTask.ofStreaming("go", token -> { });

        LlmCompletion result = ReactExecutionSupport.streamAndCollect(new StreamingWithReasoning(),
                List.of(new LlmMessage("user", "go")), LlmCallContext.of(config, task), task,
                Instant.now().plus(config.executionTimeout()), config);

        assertEquals("Hello", result.text());
        assertEquals("streamed reasoning", result.reasoning());
    }

    @Test
    void streaming_aClientWithoutASink_hasNoReasoning() throws Exception {
        LlmClient plain = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> m, LlmCallContext c) {
                return new LlmCompletion("x", 1, 1, "stop", null);
            }
            @Override public Flow.Publisher<String> stream(List<LlmMessage> m, LlmCallContext c) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    boolean done;
                    @Override public void request(long n) {
                        if (done) return;
                        done = true;
                        subscriber.onNext("text");
                        subscriber.onComplete();
                    }
                    @Override public void cancel() { }
                });
            }
            @Override public String providerId() { return "plain"; }
        };
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").streamingEnabled(true).build())
                .executionTimeout(Duration.ofSeconds(5)).build();
        AgentTask task = AgentTask.ofStreaming("go", token -> { });

        LlmCompletion result = ReactExecutionSupport.streamAndCollect(plain, List.of(new LlmMessage("user", "go")),
                LlmCallContext.of(config, task), task, Instant.now().plus(config.executionTimeout()), config);

        assertNull(result.reasoning());
    }
}
