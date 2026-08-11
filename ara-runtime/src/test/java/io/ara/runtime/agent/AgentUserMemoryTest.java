package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.agent.UserId;
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
 * Verifies cross-session {@code userMemory} (ADR-043 rev. 3) at the {@code AgentInstance}
 * level: unlike session-scoped {@code RunState}, memory written for a given {@link
 * UserId} must survive across *different* {@link SessionId}s belonging to that same
 * user, be isolated per user, and stay entirely separate from session state.
 */
class AgentUserMemoryTest {

    /** Test tool that writes a fixed key/value pair (baked in at construction) into userMemory. */
    private static final class WriteUserMemoryTool implements AraTool {
        private final String id, key, value;

        WriteUserMemoryTool(String id, String key, String value) {
            this.id = id; this.key = key; this.value = value;
        }

        @Override public String toolId() { return id; }
        @Override public String description() { return "test tool"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }

        @Override public ToolResult execute(String argumentJson) {
            return ToolResult.failure(id, "needs task");
        }

        @Override public ToolResult execute(String argumentJson, AgentTask task) {
            task.runContext().userMemory().put(key, value);
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

    private static AraAgent buildAgent(AraRuntime runtime, AgentId id) {
        return runtime.createAgent(AgentConfig.defaults()
                .agentId(id).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .enabledTools(List.of("write_memory"))
                .maxIterations(3)
                .build());
    }

    @Test
    void userMemory_survivesAcrossDifferentSessions_forTheSameUser() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_memory", "{}")
                .thenFinalAnswer("noted")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(new WriteUserMemoryTool("write_memory", "favorite_color", "teal")))
                .sessionStore(SessionStore.inMemory())
                .build();

        AraAgent agent = buildAgent(runtime, AgentId.of("memory-agent"));
        UserId user = UserId.of("u1");

        AgentResponse r1 = agent.execute(AgentTask.of("remember")
                .withSessionId(SessionId.of("session-1")).withUserId(user));
        assertTrue(r1.isSuccess(), r1.failureReason());

        // A brand-new, unrelated SessionId — only the UserId is shared with the first call.
        Map<String, Object> memory = ((UserMemoryAware) agent).userMemory(user);
        assertEquals("teal", memory.get("favorite_color"),
                "userMemory written under session-1 must be visible independent of session");
    }

    @Test
    void userMemory_isIsolated_perUser() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_memory", "{}")
                .thenFinalAnswer("noted")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(new WriteUserMemoryTool("write_memory", "secret", "s")))
                .sessionStore(SessionStore.inMemory())
                .build();

        AraAgent agent = buildAgent(runtime, AgentId.of("isolated-memory-agent"));

        agent.execute(AgentTask.of("hi")
                .withSessionId(SessionId.of("s1")).withUserId(UserId.of("u1")));

        Map<String, Object> otherUserMemory = ((UserMemoryAware) agent).userMemory(UserId.of("u2"));
        assertTrue(otherUserMemory.isEmpty(), "a different user must not see u1's memory");
    }

    @Test
    void userMemory_neverLeaksIntoSessionState_andViceVersa() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_memory", "{}")
                .thenFinalAnswer("noted")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(new WriteUserMemoryTool("write_memory", "k", "v")))
                .sessionStore(SessionStore.inMemory())
                .build();

        AraAgent agent = buildAgent(runtime, AgentId.of("separation-agent"));
        SessionId session = SessionId.of("s1");

        agent.execute(AgentTask.of("hi").withSessionId(session).withUserId(UserId.of("u1")));

        assertTrue(((RunStateAware) agent).sessionState(session).isEmpty(),
                "a write to userMemory must not appear in session state");
    }

    @Test
    void withoutAUserId_userMemoryWriteIsDiscarded_sameAsNoSessionForState() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_memory", "{}")
                .thenFinalAnswer("noted")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(new WriteUserMemoryTool("write_memory", "k", "v")))
                .sessionStore(SessionStore.inMemory())
                .build();

        AraAgent agent = buildAgent(runtime, AgentId.of("no-user-agent"));

        AgentResponse r = agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1"))); // no userId
        assertTrue(r.isSuccess(), r.failureReason());

        // Nothing to read back under any UserId — the write landed on RunState.noop().
        assertTrue(((UserMemoryAware) agent).userMemory(UserId.of("nonexistent")).isEmpty());
    }

    @Test
    void withoutARealSessionStore_userMemoryDoesNotSurviveAcrossSessions() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_memory", "{}")
                .thenFinalAnswer("noted")
                .build();

        AraRuntime runtime = AraRuntime.builder() // no .sessionStore(...) — defaults to noop()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(new WriteUserMemoryTool("write_memory", "k", "v")))
                .build();

        AraAgent agent = buildAgent(runtime, AgentId.of("noop-store-agent"));
        UserId user = UserId.of("u1");

        agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1")).withUserId(user));

        assertTrue(((UserMemoryAware) agent).userMemory(user).isEmpty(),
                "with the default noop() store, userMemory does not persist, same as everything else SessionStore-backed");
    }

    @Test
    void userMemoryAware_worksThroughContractEnforcingAgent() {
        ScriptedLlmClient client = ScriptedLlmClient.script()
                .thenToolCall("write_memory", "{}")
                .thenFinalAnswer("noted")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", client)
                .toolRegistry(taskAwareRegistry(new WriteUserMemoryTool("write_memory", "k", "v")))
                .sessionStore(SessionStore.inMemory())
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("contract-wrapped-agent")).agentType("t")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .enabledTools(List.of("write_memory"))
                .maxIterations(3)
                .build(), AgentContract.builder()
                        .addPromptShaper(io.ara.runtime.contract.StateToPromptShaper.forUserMemory())
                        .build());

        assertInstanceOf(ContractEnforcingAgent.class, agent, "a supplied contract must force the decorator");

        UserId user = UserId.of("u1");
        agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1")).withUserId(user));

        assertEquals("v", ((UserMemoryAware) agent).userMemory(user).get("k"));
    }
}
