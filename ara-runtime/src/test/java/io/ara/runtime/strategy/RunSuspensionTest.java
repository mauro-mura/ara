package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.PendingApproval;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-048 D2: when a tool call needs a human's approval and the run can be resumed, the run
 * stops <em>before</em> the call, is saved, and reports that it is waiting — instead of parking
 * a thread, and the session with it, until someone answers.
 */
class RunSuspensionTest {

    private static final AtomicInteger EXECUTIONS = new AtomicInteger();

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) {
            EXECUTIONS.incrementAndGet();
            return ToolResult.success("echo", "ECHO");
        }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return ids.contains("echo") ? List.of(ECHO) : List.of(); }
        @Override public Optional<AraTool> findById(String id) { return "echo".equals(id) ? Optional.of(ECHO) : Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return ECHO.execute(json); }
    };

    /**
     * Records what it was asked. With {@code answerAtOnce} it approves immediately — what a run
     * that blocks on the gate needs to get on; without, it keeps the question open and never
     * answers, as an operator who has not decided yet, so a suspended run stays suspended.
     */
    private static final class ApprovingGate implements ApprovalGate {
        final List<ApprovalRequest> asked = new CopyOnWriteArrayList<>();
        private final boolean answerAtOnce;

        ApprovingGate(boolean answerAtOnce) { this.answerAtOnce = answerAtOnce; }

        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            asked.add(request);
            return answerAtOnce
                    ? CompletableFuture.completedFuture(new ApprovalDecision.Approved())
                    : new CompletableFuture<>();
        }
        @Override public void submit(String requestId, ApprovalDecision decision) { throw new UnsupportedOperationException(); }
        @Override public List<ApprovalRequest> getPendingRequests() { return List.copyOf(asked); }
    }

    private static ScriptedLlmClient oneToolCallThenDone() {
        return ScriptedLlmClient.script().thenToolCall("echo", "{\"text\":\"hi\"}").thenFinalAnswer("done").build();
    }

    private record Outcome(AgentResponse response, List<ApprovalRequest> gateAsked, CheckpointStore store,
                           AgentResponse secondRun) {}

    private static Outcome run(CheckpointStore store, String strategy) {
        return run(store, strategy, true);
    }

    /** @param answerAtOnce whether the gate approves straight away; see {@link ApprovingGate} */
    private static Outcome run(CheckpointStore store, String strategy, boolean answerAtOnce) {
        EXECUTIONS.set(0);
        ApprovingGate gate = new ApprovingGate(answerAtOnce);
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient(oneToolCallThenDone())
                .toolRegistry(REGISTRY)
                .approvalGate(gate)
                .checkpointStore(store)
                .build()) {
            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentId(AgentId.of("hitl-agent")).agentType("t").primaryLlm(LlmProfile.of("default"))
                    .plannerStrategy(strategy).enabledTools(List.of("echo"))
                    .humanApprovalRequired(true).maxIterations(5).build());
            AgentResponse response = agent.execute(AgentTask.of("say hi").withSessionId(SessionId.of("s1")));
            // The session must not be left locked or half-used by the suspension.
            AgentResponse again = agent.execute(AgentTask.of("again").withSessionId(SessionId.of("s1")));
            return new Outcome(response, gate.asked, store, again);
        }
    }

    @Test
    void aGatedCall_stopsTheRunBeforeTheToolRuns_andReportsWhatItWaitsFor() {
        Outcome outcome = run(CheckpointStore.inMemory(), "react", false);
        AgentResponse response = outcome.response();

        assertEquals(AgentState.AWAITING_APPROVAL, response.finalState());
        assertFalse(response.isSuccess(), "a suspended run has no answer");
        assertTrue(response.failureReasonOpt().isEmpty(), "and it did not fail either");
        assertEquals(1, response.pendingApprovals().size());
        assertEquals("echo", response.pendingApprovals().get(0).action());
        assertEquals("hitl-agent", response.pendingApprovals().get(0).agentId());
        assertEquals(1, response.iterationsUsed());
        assertEquals(0, EXECUTIONS.get(), "the call that needs approval must not have run");
    }

    @Test
    void theQuestionIsPutToTheGate_withoutAThreadWaitingOnIt() {
        Outcome outcome = run(CheckpointStore.inMemory(), "react", false);

        assertEquals(1, outcome.gateAsked().size(),
                "the question is put to the gate, so the operator can see it...");
        assertEquals(0, EXECUTIONS.get(), "...but the run does not wait for it: execute() already returned");
    }

    @Test
    void theCheckpointKeepsTheWholeTurn_soResumingRunsItAsOne() {
        Outcome outcome = run(CheckpointStore.inMemory(), "react", false);
        RunCheckpoint checkpoint = outcome.store()
                .loadLatest(SessionId.of("s1"), outcome.response().taskId()).orElseThrow();

        PendingApproval pending = checkpoint.pendingApproval();
        assertNotNull(pending, "the run was saved as suspended, not at an ordinary boundary");
        assertEquals(outcome.response().pendingApprovals().get(0).requestId(), pending.requests().get(0).requestId());
        assertEquals(1, pending.calls().size());
        assertEquals("echo", pending.calls().get(0).toolId());
        assertTrue(pending.calls().get(0).argumentJson().contains("hi"));
        assertEquals(1, checkpoint.iteration());
        assertTrue(checkpoint.workingMemory().stream().anyMatch(m -> m.role().startsWith("assistant")),
                "the model's tool-call message is already in the saved memory");
    }

    @Test
    void theSessionIsFreeAfterTheSuspension() {
        Outcome outcome = run(CheckpointStore.inMemory(), "react", false);

        // The script has one step left, a final answer: the second run reaching it proves the
        // session was neither busy nor left holding the suspended turn's memory.
        assertTrue(outcome.secondRun().isSuccess(), outcome.secondRun().failureReason());
        assertEquals("done", outcome.secondRun().content());
    }

    @Test
    void withoutAStore_theRunBlocksOnTheGateAsBefore() {
        Outcome outcome = run(CheckpointStore.noop(), "react");

        assertTrue(outcome.response().isSuccess(), "no store, nowhere to resume from: the old behaviour");
        assertEquals(1, outcome.gateAsked().size());
        assertEquals(1, EXECUTIONS.get());
    }

    @Test
    void aStrategyThatCannotResume_blocksOnTheGateAsBefore() {
        Outcome outcome = run(CheckpointStore.inMemory(), "plan_execute");

        assertFalse(outcome.response().finalState() == AgentState.AWAITING_APPROVAL,
                "plan_execute cannot be resumed, so it must not suspend");
        assertTrue(outcome.store().loadLatest(SessionId.of("s1"), outcome.response().taskId()).isEmpty(),
                "and it must leave no suspended run behind");
    }

    @Test
    void aStoreThatCannotSave_leavesTheRunBlockingRatherThanLosingIt() {
        CheckpointStore failing = new CheckpointStore() {
            @Override public void save(RunCheckpoint checkpoint) { throw new IllegalStateException("disk full"); }
            @Override public Optional<RunCheckpoint> loadLatest(SessionId s, String t) { return Optional.empty(); }
            @Override public void delete(SessionId s, String t) { }
            @Override public List<RunCheckpoint> awaitingApproval() { return List.of(); }
        };

        Outcome outcome = run(failing, "react");

        assertTrue(outcome.response().isSuccess(), "a run is only suspended if it could be saved");
        assertEquals(1, EXECUTIONS.get());
    }
}
