package io.ara.runtime.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-048 D2 end to end, with the real in-memory gate: a run that stops for an approval shows
 * up in the operator's list, a decision submitted there continues it, and the same holds after
 * the process restarted — the checkpoint in the store is then the only thing that remembers.
 */
class RunApprovalFlowTest {

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

    private static AgentConfig agentConfig() {
        return AgentConfig.defaults().agentId(AgentId.of("hitl-agent")).agentType("t")
                .primaryLlm(LlmProfile.of("default")).plannerStrategy("react").enabledTools(List.of("echo"))
                .humanApprovalRequired(true).maxIterations(6).build();
    }

    private static AraRuntime runtime(ScriptedLlmClient llm, InMemoryApprovalGate gate, CheckpointStore store,
                                      CompletableFuture<AgentResponse> resumed) {
        return AraRuntime.builder().llmClient(llm).toolRegistry(REGISTRY).approvalGate(gate)
                .checkpointStore(store).resumedRuns(resumed::complete).build();
    }

    private static AgentResponse suspend(AraAgent agent) {
        AgentResponse suspended = agent.execute(AgentTask.of("say hi").withSessionId(SessionId.of("s1")));
        assertEquals(AgentState.AWAITING_APPROVAL, suspended.finalState());
        return suspended;
    }

    @Test
    void aSuspendedRun_appearsInTheOperatorsList_andADecisionThereContinuesIt() throws Exception {
        RAN_WITH.clear();
        CompletableFuture<AgentResponse> resumed = new CompletableFuture<>();
        try (InMemoryApprovalGate gate = new InMemoryApprovalGate();
             AraRuntime runtime = runtime(ScriptedLlmClient.script().thenToolCall("echo", "{\"x\":1}")
                     .thenFinalAnswer("done").build(), gate, CheckpointStore.inMemory(), resumed)) {
            AgentResponse suspended = suspend(runtime.createAgent(agentConfig()));

            List<ApprovalRequest> pending = gate.getPendingRequests();
            assertEquals(1, pending.size(), "the operator can see what the run is waiting for");
            assertEquals(suspended.pendingApprovals().get(0).requestId(), pending.get(0).requestId());
            assertTrue(RAN_WITH.isEmpty(), "and nothing ran while nobody had decided");

            gate.submit(pending.get(0).requestId(), new ApprovalDecision.Approved());
            AgentResponse finished = resumed.get(5, TimeUnit.SECONDS);

            assertTrue(finished.isSuccess(), finished.failureReason());
            assertEquals("done", finished.content());
            assertEquals(suspended.taskId(), finished.taskId());
            assertEquals(List.of("{\"x\":1}"), RAN_WITH);
            assertTrue(gate.getPendingRequests().isEmpty());
        }
    }

    @Test
    void aRefusalSubmittedThroughTheGate_keepsTheCallFromRunning() throws Exception {
        RAN_WITH.clear();
        CompletableFuture<AgentResponse> resumed = new CompletableFuture<>();
        try (InMemoryApprovalGate gate = new InMemoryApprovalGate();
             AraRuntime runtime = runtime(ScriptedLlmClient.script().thenToolCall("echo", "{\"x\":1}")
                     .thenFinalAnswer("done").build(), gate, CheckpointStore.inMemory(), resumed)) {
            suspend(runtime.createAgent(agentConfig()));

            gate.submit(gate.getPendingRequests().get(0).requestId(), new ApprovalDecision.Rejected("no"));
            AgentResponse finished = resumed.get(5, TimeUnit.SECONDS);

            assertTrue(finished.isSuccess(), finished.failureReason());
            assertTrue(RAN_WITH.isEmpty(), "a refused call must not run");
        }
    }

    @Test
    void afterARestart_theCheckpointAloneBringsTheQuestionBack_andADecisionContinuesTheRun() throws Exception {
        RAN_WITH.clear();
        CheckpointStore store = CheckpointStore.inMemory();   // stands for the durable store that survives the process
        String requestId;
        String taskId;

        try (InMemoryApprovalGate firstGate = new InMemoryApprovalGate();
             AraRuntime first = runtime(ScriptedLlmClient.script().thenToolCall("echo", "{\"x\":1}").build(),
                     firstGate, store, new CompletableFuture<>())) {
            AgentResponse suspended = suspend(first.createAgent(agentConfig()));
            requestId = suspended.pendingApprovals().get(0).requestId();
            taskId = suspended.taskId();
        }   // the process "dies": its gate, with the pending request, is gone

        CompletableFuture<AgentResponse> resumed = new CompletableFuture<>();
        try (InMemoryApprovalGate secondGate = new InMemoryApprovalGate();
             AraRuntime second = runtime(ScriptedLlmClient.script().thenFinalAnswer("done").build(),
                     secondGate, store, resumed)) {
            assertEquals(List.of(requestId), secondGate.getPendingRequests().stream().map(ApprovalRequest::requestId).toList(),
                    "the restarted runtime puts the question back in front of the operator");

            second.createAgent(agentConfig());   // the application registers its agents after build()
            secondGate.submit(requestId, new ApprovalDecision.Approved());
            AgentResponse finished = resumed.get(5, TimeUnit.SECONDS);

            assertTrue(finished.isSuccess(), finished.failureReason());
            assertEquals(taskId, finished.taskId());
            assertEquals(List.of("{\"x\":1}"), RAN_WITH, "the call ran once, after the restart, with its original arguments");
            assertTrue(store.awaitingApproval().isEmpty());
        }
    }
}
