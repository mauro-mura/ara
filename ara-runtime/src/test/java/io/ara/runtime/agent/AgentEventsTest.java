package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentEvent;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.StepType;
import io.ara.core.agent.ToolCallEvent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run's event channel, end to end through the runtime: what a listener sees for one run, for
 * a run that delegates, and for runs that are refused or fail.
 */
class AgentEventsTest {

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success("echo", "ECHO"); }
    };

    private static final ToolRegistry ECHO_REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return ids.contains("echo") ? List.of(ECHO) : List.of(); }
        @Override public Optional<AraTool> findById(String id) { return "echo".equals(id) ? Optional.of(ECHO) : Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return ECHO.execute(json); }
    };

    private static AgentConfig.Builder agent(String id, String model) {
        return AgentConfig.defaults().agentId(AgentId.of(id)).agentType("t")
                .primaryLlm(LlmProfile.of(model)).plannerStrategy("react");
    }

    private static List<AgentEvent> of(List<AgentEvent> events, String agentId) {
        return events.stream().filter(e -> e.agentId().equals(agentId)).toList();
    }

    private static <T extends AgentEvent> List<T> ofType(List<AgentEvent> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<ExecutionStep> steps(List<AgentEvent> events) {
        return ofType(events, AgentEvent.StepRecorded.class).stream().map(AgentEvent.StepRecorded::step).toList();
    }

    private static void assertWellFormed(List<AgentEvent> runEvents) {
        assertInstanceOf(AgentEvent.RunStarted.class, runEvents.get(0), "a run starts with RunStarted");
        assertInstanceOf(AgentEvent.RunFinished.class, runEvents.get(runEvents.size() - 1), "and ends with RunFinished");
        assertEquals(1, ofType(runEvents, AgentEvent.RunStarted.class).size());
        assertEquals(1, ofType(runEvents, AgentEvent.RunFinished.class).size());
        for (int i = 0; i < runEvents.size(); i++) {
            assertEquals(i, runEvents.get(i).seq(), "seq is contiguous from 0 within one run");
        }
    }

    @Test
    void aRunWithATool_isStartedThenItsSteps_thenFinished() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().thenToolCall("echo", "{}").thenFinalAnswer("done").build())
                .toolRegistry(ECHO_REGISTRY).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").enabledTools(List.of("echo")).build());

            AgentResponse response = a.execute(AgentTask.of("go").withEventListener(events::add));

            assertTrue(response.isSuccess(), response.failureReason());
            assertWellFormed(events);
            assertEquals(response.steps(), steps(events), "the announced steps are the response's steps");
            assertTrue(steps(events).stream().anyMatch(s -> s.type() == StepType.OBSERVATION
                    && s.content().contains("ECHO")), "the tool's result is an event, not only the call");

            AgentEvent.RunStarted started = ofType(events, AgentEvent.RunStarted.class).get(0);
            assertEquals(response.taskId(), started.taskId());
            assertEquals("solo", started.agentId());
            assertNull(started.parentTaskId(), "nobody delegated this run");

            AgentEvent.RunFinished finished = ofType(events, AgentEvent.RunFinished.class).get(0);
            assertTrue(finished.success());
            assertEquals(response.iterationsUsed(), finished.iterationsUsed());
            assertEquals(response.inputTokens(), finished.inputTokens());
            assertEquals(response.estimatedCost(), finished.estimatedCost());
            assertEquals(response.finalState(), finished.finalState());
        }
    }

    @Test
    void aDelegatedAgent_sharesTheListener_andNamesItsParent() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("boss", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"sub-task\"}")
                        .thenFinalAnswer("boss done").build())
                .llmClient("worker-model", ScriptedLlmClient.script().thenFinalAnswer("worker done").build())
                .build()) {
            runtime.createAgent(agent("worker", "worker-model").build());
            AraAgent boss = runtime.createAgent(agent("boss", "boss").enabledTools(List.of("delegate_task")).build());

            AgentResponse response = boss.execute(AgentTask.of("go").withEventListener(events::add));

            assertTrue(response.isSuccess(), response.failureReason());
            List<AgentEvent> bossEvents = of(events, "boss");
            List<AgentEvent> workerEvents = of(events, "worker");
            assertWellFormed(bossEvents);
            assertWellFormed(workerEvents);

            assertNull(bossEvents.get(0).parentTaskId());
            assertEquals(response.taskId(), workerEvents.get(0).parentTaskId(), "the worker's parent is the boss's task");
            assertTrue(!workerEvents.get(0).taskId().equals(response.taskId()), "the worker runs as a task of its own");
            assertEquals(bossEvents.get(0).correlationId() == null ? response.taskId() : bossEvents.get(0).correlationId(),
                    workerEvents.get(0).correlationId(), "the whole chain is one workflow");

            // Delegation is synchronous: the worker's whole run sits between the boss's call and its result.
            int call = events.indexOf(bossEvents.stream().filter(e -> e instanceof AgentEvent.StepRecorded s
                    && s.step().type() == StepType.TOOL_CALL).findFirst().orElseThrow());
            int observation = events.indexOf(bossEvents.stream().filter(e -> e instanceof AgentEvent.StepRecorded s
                    && s.step().type() == StepType.OBSERVATION).findFirst().orElseThrow());
            assertTrue(call < events.indexOf(workerEvents.get(0)));
            assertTrue(events.indexOf(workerEvents.get(workerEvents.size() - 1)) < observation);
        }
    }

    @Test
    void aSubAgentOfASubAgent_namesItsDirectParent_notTheRoot() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("boss", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"middle\",\"task\":\"go on\"}")
                        .thenFinalAnswer("boss done").build())
                .llmClient("middle-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"leaf\",\"task\":\"go on\"}")
                        .thenFinalAnswer("middle done").build())
                .llmClient("leaf-model", ScriptedLlmClient.script().thenFinalAnswer("leaf done").build())
                .build()) {
            runtime.createAgent(agent("leaf", "leaf-model").build());
            runtime.createAgent(agent("middle", "middle-model").enabledTools(List.of("delegate_task")).build());
            AraAgent boss = runtime.createAgent(agent("boss", "boss").enabledTools(List.of("delegate_task")).build());

            AgentResponse response = boss.execute(AgentTask.of("go").withEventListener(events::add));

            assertTrue(response.isSuccess(), response.failureReason());
            String bossTask = of(events, "boss").get(0).taskId();
            String middleTask = of(events, "middle").get(0).taskId();
            assertEquals(bossTask, of(events, "middle").get(0).parentTaskId());
            assertEquals(middleTask, of(events, "leaf").get(0).parentTaskId(), "the leaf's parent is the middle agent");
            assertWellFormed(of(events, "leaf"));
        }
    }

    @Test
    void aListenerThatThrows_doesNotStopTheRun_andKeepsReceivingEvents() {
        AtomicInteger received = new AtomicInteger();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().thenToolCall("echo", "{}").thenFinalAnswer("done").build())
                .toolRegistry(ECHO_REGISTRY).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").enabledTools(List.of("echo")).build());

            AgentResponse response = a.execute(AgentTask.of("go").withEventListener(event -> {
                received.incrementAndGet();
                throw new IllegalStateException("a listener with a bug");
            }));

            assertTrue(response.isSuccess(), "the run is not affected by its observer: " + response.failureReason());
            // started + every step + finished
            assertEquals(response.steps().size() + 2, received.get(), "delivery goes on after the first failure");
        }
    }

    @Test
    void aRefusedRun_stillGetsStartedAndFinished() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().thenFinalAnswer("never").build()).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").build());
            a.terminate();

            AgentResponse response = a.execute(AgentTask.of("go").withEventListener(events::add));

            assertTrue(!response.isSuccess());
            assertEquals(2, events.size(), "a refusal has no steps, but it is a run");
            assertWellFormed(events);
            AgentEvent.RunFinished finished = ofType(events, AgentEvent.RunFinished.class).get(0);
            assertTrue(!finished.success());
            assertTrue(finished.failureReason().contains("terminated"), finished.failureReason());
        }
    }

    /** A model whose first call waits to be released, so a run can be held in flight on purpose. */
    private static final class HeldClient implements LlmClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmCompletion complete(List<io.ara.core.llm.LlmMessage> messages, io.ara.core.llm.LlmCallContext context) {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "held"; }
    }

    @Test
    void aRunRefusedBecauseTheSessionIsBusy_stillGetsStartedAndFinished() throws Exception {
        HeldClient held = new HeldClient();
        List<AgentEvent> refused = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder().llmClient("m", held).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").build());   // REJECT is the default policy
            SessionId session = SessionId.of("busy-session");
            Thread running = new Thread(() -> a.execute(AgentTask.of("first").withSessionId(session)));
            running.start();
            try {
                assertTrue(held.entered.await(5, TimeUnit.SECONDS), "the first run must be in flight");

                AgentResponse second = a.execute(AgentTask.of("second").withSessionId(session)
                        .withEventListener(refused::add));

                assertTrue(!second.isSuccess());
                assertTrue(second.failureReason().contains("Session busy"), second.failureReason());
                assertEquals(2, refused.size(), "a refusal has no steps, but it is a run: " + refused);
                assertWellFormed(refused);
                assertTrue(!ofType(refused, AgentEvent.RunFinished.class).get(0).success());
            } finally {
                held.release.countDown();
                running.join(5_000);
            }
        }
    }

    @Test
    void aStrategyThatBlowsUp_endsTheRunWithAFailure() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ExecutionStrategy boom = new ExecutionStrategy() {
            @Override public String strategyName() { return "boom"; }
            @Override public ExecutionResult execute(AgentTask task, LlmClient llm, MemoryManager memory,
                                                     ToolRegistry tools, AgentConfig config) {
                throw new IllegalStateException("kaboom");
            }
        };
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().build()).extraStrategies(boom).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").plannerStrategy("boom").build());

            try {
                a.execute(AgentTask.of("go").withEventListener(events::add));
            } catch (RuntimeException propagated) {
                // either way is acceptable to the caller; the events must be complete regardless
            }

            assertWellFormed(events);
            AgentEvent.RunFinished finished = ofType(events, AgentEvent.RunFinished.class).get(0);
            assertTrue(!finished.success());
            assertTrue(finished.failureReason().contains("kaboom"), finished.failureReason());
        }
    }

    @Test
    void theOlderCallbacks_andTheListener_bothFireForTheSameToolCall() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        List<ToolCallEvent> callbacks = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().thenToolCall("echo", "{\"x\":1}").thenFinalAnswer("done").build())
                .toolRegistry(ECHO_REGISTRY).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").enabledTools(List.of("echo")).build());

            a.execute(AgentTask.of("go").withEventListener(events::add).withToolCallCallback(callbacks::add));

            assertEquals(1, callbacks.size());
            List<ExecutionStep> calls = steps(events).stream().filter(s -> s.type() == StepType.TOOL_CALL).toList();
            assertEquals(1, calls.size());
            assertEquals(callbacks.get(0).toolId(), calls.get(0).toolId());
            assertEquals(callbacks.get(0).argumentJson(), calls.get(0).arguments());
        }
    }

    @Test
    void parallelTools_areRecordedFromOneThread_inOrder() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        Set<Long> threads = new HashSet<>();
        LlmCompletion twoCalls = new LlmCompletion("", 10, 10, "tool_calls", null, null,
                List.of(new ToolCallEntry("c1", "echo", "{\"n\":1}"), new ToolCallEntry("c2", "echo", "{\"n\":2}")), false);
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().then(twoCalls).thenFinalAnswer("done").build())
                .toolRegistry(ECHO_REGISTRY).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").enabledTools(List.of("echo")).build());

            AgentResponse response = a.execute(AgentTask.of("go").withEventListener(event -> {
                synchronized (threads) { threads.add(Thread.currentThread().threadId()); }
                events.add(event);
            }));

            assertTrue(response.isSuccess(), response.failureReason());
            List<StepType> types = steps(events).stream().map(ExecutionStep::type).toList();
            assertEquals(2, types.stream().filter(t -> t == StepType.TOOL_CALL).count(), "both calls were made: " + types);
            assertWellFormed(events);
            assertEquals(1, threads.size(), "one task's events come from a single thread, even with parallel tools");
            // The results are recorded together, after all the tools finish (a known limit, see the ADR).
            int lastCall = types.lastIndexOf(StepType.TOOL_CALL);
            int firstObservation = types.indexOf(StepType.OBSERVATION);
            assertTrue(lastCall < firstObservation, "every call is announced before the first result: " + types);
        }
    }

    @Test
    void withoutAListener_nothingIsEmitted_andTheRunIsUnchanged() {
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().thenFinalAnswer("plain").build()).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").build());

            AgentResponse response = a.execute(AgentTask.of("go"));

            assertTrue(response.isSuccess());
            assertEquals("plain", response.content());
        }
    }

    @Test
    void eachRunNumbersItsOwnEvents_fromZero() {
        List<AgentEvent> first = new ArrayList<>();
        List<AgentEvent> second = new ArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("m", ScriptedLlmClient.script().thenFinalAnswer("one").thenFinalAnswer("two").build()).build()) {
            AraAgent a = runtime.createAgent(agent("solo", "m").build());

            a.execute(AgentTask.of("a").withEventListener(first::add));
            a.execute(AgentTask.of("b").withEventListener(second::add));

            assertEquals(0, first.get(0).seq());
            assertEquals(0, second.get(0).seq(), "a second run on the same agent starts again at 0");
        }
    }
}
