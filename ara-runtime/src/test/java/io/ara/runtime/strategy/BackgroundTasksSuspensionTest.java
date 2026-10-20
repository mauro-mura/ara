package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.Reversibility;
import io.ara.core.tool.SideEffects;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run that starts a background task and then stops to wait for an approval. A future in
 * flight cannot be saved in a checkpoint, so the task is stopped, and the model is told so in
 * the saved run: what it reads on resuming must not promise a result that cannot arrive.
 */
class BackgroundTasksSuspensionTest {

    private static final AraTool GATED = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success("echo", "ECHO"); }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return ids.contains("echo") ? List.of(GATED) : List.of(); }
        @Override public Optional<AraTool> findById(String id) { return "echo".equals(id) ? Optional.of(GATED) : Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return GATED.execute(json); }
        // Only this tool needs an approval. The agent-wide flag would gate every call, including
        // the one that starts the background task, and the run would stop before it began.
        @Override public Optional<ToolSpec> specFor(String id) {
            return "echo".equals(id)
                    ? Optional.of(ToolSpec.builtin("echo", SideEffects.EXTERNAL_WRITE, new Reversibility.IrreversibleHighImpact()))
                    : Optional.empty();
        }
    };

    /** Keeps every question open, as an operator who has not decided yet. */
    private static final class UnansweredGate implements ApprovalGate {
        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) { return new CompletableFuture<>(); }
        @Override public void submit(String requestId, ApprovalDecision decision) { throw new UnsupportedOperationException(); }
        @Override public List<ApprovalRequest> getPendingRequests() { return List.of(); }
    }

    /** Blocks inside its call until interrupted, and notes that it was. */
    private static final class BlockedModel implements LlmClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            entered.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: never used", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "blocked-worker-model"; }
    }

    @Test
    void aSuspendedRun_stopsItsBackgroundTasks_andSavesANoteTheResumedRunWillRead() throws Exception {
        BlockedModel worker = new BlockedModel();
        CheckpointStore store = CheckpointStore.inMemory();
        // The echo call must come after the task has really started, or it would be cancelled before running at all.
        LlmClient coordinator = new LlmClient() {
            private final LlmClient script = ScriptedLlmClient.script()
                    .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"slow job\","
                            + "\"wait\":false,\"label\":\"slow\"}")
                    .thenToolCall("echo", "{}").build();
            private int calls;
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                if (++calls == 2) {
                    try {
                        assertTrue(worker.entered.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return script.complete(messages, context);
            }
            @Override public String providerId() { return "coordinator-model"; }
        };
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", coordinator)
                .llmClient("worker-model", worker)
                .toolRegistry(REGISTRY)
                .approvalGate(new UnansweredGate())
                .checkpointStore(store)
                .build()) {
            runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("worker")).agentType("t")
                    .primaryLlm(LlmProfile.of("worker-model")).plannerStrategy("react").maxIterations(3).build());
            AraAgent agent = runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("coordinator")).agentType("t")
                    .primaryLlm(LlmProfile.of("coordinator-model")).plannerStrategy("react")
                    .enabledTools(List.of("delegate_task", "echo"))
                    .asyncDelegation(AsyncDelegation.defaults()).maxIterations(6).build());

            AgentResponse response = agent.execute(AgentTask.of("start it, then do the gated thing")
                    .withSessionId(SessionId.of("s1")));

            assertEquals(AgentState.AWAITING_APPROVAL, response.finalState());
            for (int i = 0; i < 100 && !worker.interrupted.get(); i++) {
                Thread.sleep(50);
            }
            assertTrue(worker.interrupted.get(), "the background task must not keep running behind a suspended run");
            RunCheckpoint checkpoint = store.awaitingApproval().get(0);
            String saved = checkpoint.workingMemory().stream().map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
            assertTrue(saved.contains("[automated] These background tasks were cancelled because the run stopped "
                    + "to wait for an approval"), saved);
            assertTrue(saved.contains("\"slow\" (worker)"), saved);
        }
    }
}
