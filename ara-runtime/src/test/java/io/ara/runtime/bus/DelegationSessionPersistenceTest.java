package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.agent.RunStateAware;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof that the two persistence bugs identified in this session are fixed:
 * a delegated sub-agent's conversation history is no longer orphaned under an ephemeral
 * session, and {@link io.ara.core.agent.DelegateStateAccess#OVERLAY}/{@code ISOLATED}
 * writes now survive beyond the single delegated call, exactly like {@code SHARED}
 * already did by coincidence.
 */
class DelegationSessionPersistenceTest {

    @Test
    void repeatedDelegations_fromTheSameCallerSession_accumulateOnTheWorkersOwnDerivedSession() {
        SessionStore sharedStore = SessionStore.inMemory();
        SessionId coordinatorSession = SessionId.of("coordinator-session");

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"first sub-task\"}")
                        .thenFinalAnswer("done 1")
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"second sub-task\"}")
                        .thenFinalAnswer("done 2")
                        .build())
                .llmClient("worker-model", ScriptedLlmClient.script()
                        .thenFinalAnswer("worked on it 1")
                        .thenFinalAnswer("worked on it 2")
                        .build())
                .sessionStore(sharedStore)
                .build();

        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model"))
                .plannerStrategy("react")
                .maxConversationTurns(5)
                .maxIterations(3)
                .build());

        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task"))
                .maxIterations(3)
                .build());

        AgentResponse r1 = coordinator.execute(
                AgentTask.of("please delegate the first sub-task").withSessionId(coordinatorSession));
        assertTrue(r1.isSuccess(), r1.failureReason());

        AgentResponse r2 = coordinator.execute(
                AgentTask.of("please delegate the second sub-task").withSessionId(coordinatorSession));
        assertTrue(r2.isSuccess(), r2.failureReason());

        SessionId workerSession = AgentDelegationTool.delegateSessionId(coordinatorSession, "worker");

        // The worker's own conversation history, read straight from the shared store under
        // its derived session — both delegated turns must accumulate there instead of each
        // landing on its own orphaned ephemeral session (today's bug, fixed by this change).
        List<ConversationTurn> fromStore = sharedStore.loadTurns(workerSession);
        assertEquals(2, fromStore.size(), "both delegated turns must accumulate on the worker's own derived session");
        assertEquals("first sub-task", fromStore.get(0).userInput());
        assertEquals("second sub-task", fromStore.get(1).userInput());
    }

    @Test
    void overlayDelegation_writesSurviveInTheSharedStore_underTheWorkersDerivedSession() {
        SessionStore sharedStore = SessionStore.inMemory();
        SessionId coordinatorSession = SessionId.of("coordinator-session-2");

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"write a fact\"}")
                        .thenFinalAnswer("done")
                        .build())
                .llmClient("worker-model", ScriptedLlmClient.script()
                        .thenToolCall("write_state", "{}")
                        .thenFinalAnswer("noted")
                        .build())
                .toolRegistryFactory(agentCfg -> {
                    if (!agentCfg.agentId().value().equals("worker")) return io.ara.core.tool.ToolRegistry.empty();
                    return new io.ara.core.tool.ToolRegistry() {
                        private final io.ara.core.tool.AraTool writeState = new io.ara.core.tool.AraTool() {
                            @Override public String toolId() { return "write_state"; }
                            @Override public String description() { return "test"; }
                            @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
                            @Override public io.ara.core.tool.ToolResult execute(String argumentJson) {
                                return io.ara.core.tool.ToolResult.failure("write_state", "needs task");
                            }
                            @Override public io.ara.core.tool.ToolResult execute(String argumentJson, AgentTask task) {
                                task.runContext().state().put("fact", "42");
                                return io.ara.core.tool.ToolResult.success("write_state", "ok");
                            }
                        };
                        @Override public List<io.ara.core.tool.AraTool> resolveEnabled(List<String> ids) { return List.of(writeState); }
                        @Override public java.util.Optional<io.ara.core.tool.AraTool> findById(String id) {
                            return "write_state".equals(id) ? java.util.Optional.of(writeState) : java.util.Optional.empty();
                        }
                        @Override public io.ara.core.tool.ToolResult execute(String id, String json) { return writeState.execute(json); }
                        @Override public io.ara.core.tool.ToolResult execute(String id, String json, AgentTask task) { return writeState.execute(json, task); }
                    };
                })
                .sessionStore(sharedStore)
                .build();

        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("write_state"))
                .maxIterations(3)
                .build());

        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task")) // default DelegateStateAccess.OVERLAY
                .maxIterations(3)
                .build());

        AgentResponse r = coordinator.execute(
                AgentTask.of("please delegate writing a fact").withSessionId(coordinatorSession));
        assertTrue(r.isSuccess(), r.failureReason());

        SessionId workerSession = AgentDelegationTool.delegateSessionId(coordinatorSession, "worker");
        assertEquals("42", sharedStore.loadState(workerSession).get("fact"),
                "under OVERLAY, the worker's write must now persist under its own derived session "
                        + "instead of vanishing with a throwaway in-memory RunState");

        // The coordinator's own state must remain untouched (OVERLAY confines writes).
        Object coordinatorFact = ((RunStateAware) coordinator).sessionState(coordinatorSession).get("fact");
        assertNull(coordinatorFact, "OVERLAY writes must stay on the delegate's private layer");
    }
}
