package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-048 D2, the second half: a run suspended before a gated tool call is continued with the
 * human's decisions, completes the turn it stopped in — running, changing or refusing each call
 * as decided — and goes on. The decision is applied through the same paths as one that arrives
 * while a thread waits, and it covers exactly the call that was asked about.
 */
class RunResumeAfterApprovalTest {

    /** The arguments each call to {@code echo} actually ran with. */
    private static final List<String> RAN_WITH = new CopyOnWriteArrayList<>();

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) {
            RAN_WITH.add(argumentJson);
            return ToolResult.success("echo", "ECHO");
        }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return ids.contains("echo") ? List.of(ECHO) : List.of(); }
        @Override public Optional<AraTool> findById(String id) { return "echo".equals(id) ? Optional.of(ECHO) : Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return ECHO.execute(json); }
    };

    /**
     * Takes the questions of suspended runs and never answers them: these tests resume the run
     * themselves, with the decisions they want, so nothing may continue it behind their back.
     */
    private static final ApprovalGate UNANSWERING_GATE = new ApprovalGate() {
        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            return new CompletableFuture<>();
        }
        @Override public void submit(String requestId, ApprovalDecision decision) { throw new UnsupportedOperationException(); }
        @Override public List<ApprovalRequest> getPendingRequests() { return List.of(); }
    };

    private static final String ARGS = "{\"text\":\"hi\"}";

    /** What a test does with an agent and the store its checkpoints go to. */
    private interface Scenario { void run(AraAgent agent, CheckpointStore store); }

    private static void withAgent(ScriptedLlmClient llm, Scenario scenario) {
        RAN_WITH.clear();
        CheckpointStore store = CheckpointStore.inMemory();
        try (AraRuntime runtime = AraRuntime.builder().llmClient(llm).toolRegistry(REGISTRY)
                .approvalGate(UNANSWERING_GATE).checkpointStore(store).build()) {
            scenario.run(runtime.createAgent(AgentConfig.defaults()
                    .agentId(AgentId.of("hitl-agent")).agentType("t").primaryLlm(LlmProfile.of("default"))
                    .plannerStrategy("react").enabledTools(List.of("echo"))
                    .humanApprovalRequired(true).maxIterations(6).build()), store);
        }
    }

    private static AgentResponse start(AraAgent agent) {
        return agent.execute(AgentTask.of("say hi").withSessionId(SessionId.of("s1")));
    }

    private static RunCheckpoint savedRunOf(CheckpointStore store, AgentResponse suspended) {
        assertEquals(AgentState.AWAITING_APPROVAL, suspended.finalState());
        return store.loadLatest(SessionId.of("s1"), suspended.taskId()).orElseThrow();
    }

    private static Map<String, ApprovalDecision> decide(AgentResponse suspended, ApprovalDecision decision) {
        return Map.of(suspended.pendingApprovals().get(0).requestId(), decision);
    }

    private static ScriptedLlmClient callThenAnswer() {
        return ScriptedLlmClient.script().thenToolCall("echo", ARGS).thenFinalAnswer("done").build();
    }

    @Test
    void approved_theSuspendedCallRuns_andTheRunGoesOnToItsAnswer() {
        withAgent(callThenAnswer(), (agent, store) -> {
            AgentResponse suspended = start(agent);
            RunCheckpoint checkpoint = savedRunOf(store, suspended);

            AgentResponse resumed = agent.execute(
                    AgentTask.resuming(checkpoint, decide(suspended, new ApprovalDecision.Approved())));

            assertTrue(resumed.isSuccess(), resumed.failureReason());
            assertEquals("done", resumed.content());
            assertEquals(List.of(ARGS), RAN_WITH, "the call ran once, with the arguments the model gave");
            assertEquals(suspended.taskId(), resumed.taskId());
            assertEquals(2, resumed.iterationsUsed(), "the turn it stopped in counts once, then one more to answer");
            assertTrue(store.loadLatest(SessionId.of("s1"), suspended.taskId()).isEmpty(),
                    "a run that finished leaves nothing to resume");
        });
    }

    @Test
    void modified_theCallRunsWithTheOperatorsArguments() {
        withAgent(callThenAnswer(), (agent, store) -> {
            AgentResponse suspended = start(agent);
            RunCheckpoint checkpoint = savedRunOf(store, suspended);

            AgentResponse resumed = agent.execute(AgentTask.resuming(checkpoint,
                    decide(suspended, new ApprovalDecision.Modified("{\"text\":\"edited\"}"))));

            assertTrue(resumed.isSuccess(), resumed.failureReason());
            assertEquals(List.of("{\"text\":\"edited\"}"), RAN_WITH);
        });
    }

    @Test
    void rejected_theCallNeverRuns_andTheModelIsToldWhy() {
        withAgent(callThenAnswer(), (agent, store) -> {
            AgentResponse suspended = start(agent);
            RunCheckpoint checkpoint = savedRunOf(store, suspended);

            AgentResponse resumed = agent.execute(AgentTask.resuming(checkpoint,
                    decide(suspended, new ApprovalDecision.Rejected("not today"))));

            assertTrue(resumed.isSuccess(), resumed.failureReason());
            assertTrue(RAN_WITH.isEmpty(), "a refused call must not run");
            assertTrue(resumed.steps().stream().anyMatch(step -> step.content() != null
                            && step.content().contains("Human rejected action 'echo': not today")),
                    "the refusal reaches the model as the observation of the call: " + resumed.steps());
        });
    }

    @Test
    void aDecisionCoversOnlyTheCallItWasGivenFor_notTheNextOneWithTheSameArguments() {
        // The model calls the same tool with the same arguments twice, in two turns. The
        // answer given to the first must not let the second through: it is a new action.
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("echo", ARGS).thenToolCall("echo", ARGS).thenFinalAnswer("done").build();
        withAgent(llm, (agent, store) -> {
            AgentResponse first = start(agent);
            RunCheckpoint checkpoint = savedRunOf(store, first);

            AgentResponse second = agent.execute(
                    AgentTask.resuming(checkpoint, decide(first, new ApprovalDecision.Approved())));

            assertEquals(AgentState.AWAITING_APPROVAL, second.finalState(),
                    "the second identical call needs its own approval");
            assertEquals(List.of(ARGS), RAN_WITH, "only the first call ran");
        });
    }

    @Test
    void resumingWithoutADecisionForARequest_isRefused_ratherThanRunningOrSkippingTheCall() {
        withAgent(callThenAnswer(), (agent, store) -> {
            AgentResponse suspended = start(agent);
            RunCheckpoint checkpoint = savedRunOf(store, suspended);

            assertThrows(IllegalArgumentException.class, () -> AgentTask.resuming(checkpoint));
            assertThrows(IllegalArgumentException.class,
                    () -> AgentTask.resuming(checkpoint, Map.of("other", new ApprovalDecision.Approved())));
            assertTrue(RAN_WITH.isEmpty());
        });
    }
}
