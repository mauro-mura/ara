package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
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
 * Verifies session-scoped {@code RunState} at the {@code AgentInstance} level: state
 * written by a tool call survives across multiple {@code execute()} calls on the same
 * session, is isolated per session, and is exposed read-only via {@link
 * RunStateAware#sessionState(SessionId)}.
 */
class AgentSessionRunStateTest {

    /** Test tool that writes a fixed key/value pair (baked in at construction) into RunState. */
    private static final class WriteStateTool implements AraTool {
        private final String id, key, value;

        WriteStateTool(String id, String key, String value) {
            this.id = id; this.key = key; this.value = value;
        }

        @Override public String toolId() { return id; }
        @Override public String description() { return "test tool"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }

        @Override public ToolResult execute(String argumentJson) {
            return ToolResult.failure(id, "needs task");
        }

        @Override public ToolResult execute(String argumentJson, AgentTask task) {
            task.runContext().state().put(key, value);
            return ToolResult.success(id, "ok");
        }
    }

    /** Minimal registry that forwards the task to its tools — the default degrades and drops it. */
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
    void state_persistsAcrossMultipleExecuteCalls_onSameSession() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_k1", "{}")
                .thenFinalAnswer("done1")
                .thenToolCall("write_k2", "{}")
                .thenFinalAnswer("done2")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(
                        new WriteStateTool("write_k1", "k1", "v1"),
                        new WriteStateTool("write_k2", "k2", "v2")))
                .build();

        AgentId id = AgentId.of("stateful-agent");
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(id).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .enabledTools(List.of("write_k1", "write_k2"))
                .maxIterations(5)
                .build());

        SessionId session = SessionId.of("s1");
        AgentResponse r1 = agent.execute(AgentTask.of("first").withSessionId(session));
        assertTrue(r1.isSuccess(), r1.failureReason());
        AgentResponse r2 = agent.execute(AgentTask.of("second").withSessionId(session));
        assertTrue(r2.isSuccess(), r2.failureReason());

        Map<String, Object> state = ((RunStateAware) agent).sessionState(session);
        assertEquals("v1", state.get("k1"), "state written in the first execute() call must survive into the second");
        assertEquals("v2", state.get("k2"));
    }

    @Test
    void state_isIsolated_perSession() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_k1", "{}")
                .thenFinalAnswer("done")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(new WriteStateTool("write_k1", "k1", "v1")))
                .build();

        AgentId id = AgentId.of("isolated-agent");
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(id).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .enabledTools(List.of("write_k1"))
                .maxIterations(5)
                .build());

        agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1")));

        Map<String, Object> otherSessionState = ((RunStateAware) agent).sessionState(SessionId.of("s2"));
        assertTrue(otherSessionState.isEmpty(), "a different session must not see s1's state");
    }

    @Test
    void sessionState_unknownSessionId_returnsEmptyMap() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .build();
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("never-run")).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .build());

        assertTrue(((RunStateAware) agent).sessionState(SessionId.of("never-used")).isEmpty());
    }
}
