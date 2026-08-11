package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end demonstration of why {@link SessionStore} exists: state and conversation
 * history must survive beyond a single {@code SessionManager}'s lifetime, not just a
 * single session-eviction window. Two entirely independent {@link AraRuntime}s (each
 * with its own {@code SessionManager}) sharing one {@link SessionStore} instance is the
 * sharpest way to prove this — a second runtime resuming the first one's session is
 * indistinguishable, from the store's point of view, from the same runtime surviving a
 * restart.
 */
class AgentSessionPersistenceTest {

    private static final class WriteStateTool implements AraTool {
        private final String id, key, value;
        WriteStateTool(String id, String key, String value) { this.id = id; this.key = key; this.value = value; }

        @Override public String toolId() { return id; }
        @Override public String description() { return "test tool"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.failure(id, "needs task"); }
        @Override public ToolResult execute(String argumentJson, AgentTask task) {
            task.runContext().state().put(key, value);
            return ToolResult.success(id, "ok");
        }
    }

    private static ToolRegistry taskAwareRegistry(AraTool... tools) {
        Map<String, AraTool> byId = new LinkedHashMap<>();
        for (AraTool t : tools) byId.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.copyOf(byId.values()); }
            @Override public Optional<AraTool> findById(String id) { return Optional.ofNullable(byId.get(id)); }
            @Override public ToolResult execute(String id, String json) {
                AraTool t = byId.get(id);
                return t != null ? t.execute(json) : ToolResult.failure(id, "not found");
            }
            @Override public ToolResult execute(String id, String json, AgentTask task) {
                AraTool t = byId.get(id);
                return t != null ? t.execute(json, task) : ToolResult.failure(id, "not found");
            }
        };
    }

    @Test
    void stateAndHistory_surviveAcrossIndependentRuntimeInstances_whenSharingAStore() {
        SessionStore sharedStore = SessionStore.inMemory();
        AgentId agentId = AgentId.of("resumable-agent");
        SessionId session = SessionId.of("resumable-session");

        // Runtime 1: writes a fact via a tool, and (since maxConversationTurns > 0)
        // records a conversation turn — both write through to sharedStore.
        AraRuntime runtime1 = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script()
                        .thenToolCall("write_state", "{}")
                        .thenFinalAnswer("Noted.")
                        .build())
                .toolRegistry(taskAwareRegistry(new WriteStateTool("write_state", "fact", "42")))
                .sessionStore(sharedStore)
                .build();
        AraAgent agent1 = runtime1.createAgent(AgentConfig.defaults()
                .agentId(agentId).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .enabledTools(List.of("write_state"))
                .maxConversationTurns(5)
                .maxIterations(3)
                .build());

        AgentResponse r1 = agent1.execute(AgentTask.of("Remember the fact").withSessionId(session));
        assertTrue(r1.isSuccess(), r1.failureReason());

        // Runtime 2: a completely separate AraRuntime/AgentFactory/SessionManager — no
        // object from runtime1 is reused, only the SessionStore. Same agentId, same
        // SessionId — this is the scenario a process restart would produce.
        AraRuntime runtime2 = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script().thenFinalAnswer("Recalled.").build())
                .sessionStore(sharedStore)
                .build();
        AraAgent agent2 = runtime2.createAgent(AgentConfig.defaults()
                .agentId(agentId).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .maxConversationTurns(5)
                .maxIterations(3)
                .build());

        AgentResponse r2 = agent2.execute(AgentTask.of("What's the fact?").withSessionId(session));
        assertTrue(r2.isSuccess(), r2.failureReason());

        Map<String, Object> state = ((RunStateAware) agent2).sessionState(session);
        assertEquals("42", state.get("fact"), "runtime2's fresh session must be seeded with runtime1's persisted state");

        List<ConversationTurn> history = ((SessionHistoryAware) agent2).conversationHistory(session);
        assertEquals(2, history.size(),
                "runtime2's fresh session must replay runtime1's turn from the shared store, plus its own");
        assertEquals("Remember the fact", history.get(0).userInput());
        assertEquals("What's the fact?", history.get(1).userInput());
    }

    @Test
    void withoutASharedStore_aSecondRuntimeStartsCompletelyEmpty() {
        AgentId agentId = AgentId.of("non-resumable-agent");
        SessionId session = SessionId.of("non-resumable-session");

        AraRuntime runtime1 = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script()
                        .thenToolCall("write_state", "{}")
                        .thenFinalAnswer("Noted.")
                        .build())
                .toolRegistry(taskAwareRegistry(new WriteStateTool("write_state", "fact", "42")))
                // no .sessionStore(...) — defaults to SessionStore.noop(), today's behavior
                .build();
        AraAgent agent1 = runtime1.createAgent(AgentConfig.defaults()
                .agentId(agentId).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .enabledTools(List.of("write_state"))
                .maxConversationTurns(5)
                .maxIterations(3)
                .build());
        agent1.execute(AgentTask.of("Remember the fact").withSessionId(session));

        AraRuntime runtime2 = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script().thenFinalAnswer("Recalled.").build())
                .build();
        AraAgent agent2 = runtime2.createAgent(AgentConfig.defaults()
                .agentId(agentId).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .maxConversationTurns(5)
                .maxIterations(3)
                .build());
        agent2.execute(AgentTask.of("What's the fact?").withSessionId(session));

        Map<String, Object> state = ((RunStateAware) agent2).sessionState(session);
        assertTrue(state.isEmpty(), "with the default noop() store, nothing survives a fresh SessionManager");

        List<ConversationTurn> history = ((SessionHistoryAware) agent2).conversationHistory(session);
        assertEquals(1, history.size(), "only runtime2's own turn — runtime1's was never persisted anywhere");
    }
}
