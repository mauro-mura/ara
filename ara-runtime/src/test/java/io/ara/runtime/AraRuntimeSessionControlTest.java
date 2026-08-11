package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AraRuntime#terminateSession}, {@link AraRuntime#invalidateSession}, and
 * {@link AraRuntime#activeSessionCount} mirror {@link AraRuntime#conversationHistory}'s
 * instanceof-and-fallback idiom for the {@code SessionScoped} capability (split out of
 * {@code AraAgent} itself): they work for an agent that manages sessions, an unknown
 * agent id, and an agent that manages none of it, without ever throwing.
 */
class AraRuntimeSessionControlTest {

    private static AgentConfig configFor(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))
                .plannerStrategy("react")
                .build();
    }

    @Test
    void activeSessionCount_reflectsRealSessions_forASessionScopedAgent() {
        AgentId id = AgentId.of("scoped-agent");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();
        try {
            AraAgent agent = runtime.createAgent(configFor(id));
            assertEquals(0, runtime.activeSessionCount(id));

            agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1")));
            assertEquals(1, runtime.activeSessionCount(id));

            assertDoesNotThrow(() -> runtime.terminateSession(id, SessionId.of("s1")));
            assertDoesNotThrow(() -> runtime.invalidateSession(id, SessionId.of("s1")));
            assertEquals(0, runtime.activeSessionCount(id));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void allThree_areNoOps_forAnUnknownAgentId() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();
        try {
            AgentId ghost = AgentId.of("ghost");
            assertDoesNotThrow(() -> runtime.terminateSession(ghost, SessionId.of("s1")));
            assertDoesNotThrow(() -> runtime.invalidateSession(ghost, SessionId.of("s1")));
            assertEquals(0, runtime.activeSessionCount(ghost));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void allThree_areNoOps_forAnAgentThatDoesNotManageSessions() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();
        try {
            AgentId id = AgentId.of("session-agnostic");
            runtime.registry().register(new MinimalAgent(id));

            assertDoesNotThrow(() -> runtime.terminateSession(id, SessionId.of("s1")));
            assertDoesNotThrow(() -> runtime.invalidateSession(id, SessionId.of("s1")));
            assertEquals(0, runtime.activeSessionCount(id));
        } finally {
            runtime.stop();
        }
    }

    /** The bare minimum {@code AraAgent} — no {@code Reconfigurable}, no {@code SessionScoped}. */
    private static final class MinimalAgent implements AraAgent {
        private final AgentId id;
        MinimalAgent(AgentId id) { this.id = id; }
        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() {
            return AgentConfig.defaults().agentId(id).agentType("minimal").build();
        }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0, Duration.ZERO, List.of());
        }
        @Override public void terminate() { }
    }
}
