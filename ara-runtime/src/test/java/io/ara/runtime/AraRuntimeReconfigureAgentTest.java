package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.agent.InstanceContextStore;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code AraRuntime.reconfigureAgent(...)} (ADR-039): unlike {@link
 * AraRuntimeReplaceAgentTest}'s {@code replaceAgent}, this updates the SAME {@code
 * AraAgent} instance in place — no new instance, no lost sessions.
 */
class AraRuntimeReconfigureAgentTest {

    private static AgentConfig configFor(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("react")
                .build();
    }

    private static AraRuntime runtime() {
        return AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();
    }

    @Test
    void reconfigureAgent_unknownId_throws() {
        AraRuntime runtime = runtime();
        assertThrows(IllegalArgumentException.class,
                () -> runtime.reconfigureAgent(AgentId.of("ghost"), cfg -> cfg));
    }

    @Test
    void reconfigureAgent_keepsTheSameAgentInstance_unlikeReplaceAgent() {
        AraRuntime runtime = runtime();
        AgentId id = AgentId.of("stable");
        AraAgent original = runtime.createAgent(configFor(id));

        runtime.reconfigureAgent(id, cfg -> cfg.toBuilder().maxIterations(7).build());

        assertSame(original, runtime.agent(id).orElseThrow(),
                "reconfigureAgent must update in place — replaceAgent is the one that swaps instances");
        assertEquals(7, runtime.agent(id).orElseThrow().config().maxIterations());
    }

    @Test
    void reconfigureAgent_appliesTheOperatorToTheCurrentConfig_notAStaleSnapshot() {
        AraRuntime runtime = runtime();
        AgentId id = AgentId.of("counter");
        runtime.createAgent(configFor(id).toBuilder().maxIterations(1).build());

        runtime.reconfigureAgent(id, cfg -> cfg.toBuilder().maxIterations(cfg.maxIterations() + 1).build());
        runtime.reconfigureAgent(id, cfg -> cfg.toBuilder().maxIterations(cfg.maxIterations() + 1).build());

        assertEquals(3, runtime.agent(id).orElseThrow().config().maxIterations(),
                "the second call's operator must see the first call's result, not the original config");
    }

    @Test
    void reconfigureAgent_preservesInstanceContextStoreEntry_unlikeReplaceAgent() {
        InstanceContextStore store = new InstanceContextStore();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .instanceContextStore(store)
                .build();

        AgentId id = AgentId.of("ctx-agent");
        store.set(id, Map.of("secret", "s1"));
        runtime.createAgent(configFor(id));

        runtime.reconfigureAgent(id, cfg -> cfg);

        assertEquals("s1", store.forAgent(id).get("secret"));
    }

    @Test
    void reconfigureAgent_rejectsChangingTheAgentId() {
        AraRuntime runtime = runtime();
        AgentId id = AgentId.of("fixed-id");
        runtime.createAgent(configFor(id));

        assertThrows(IllegalArgumentException.class, () ->
                runtime.reconfigureAgent(id, cfg -> cfg.toBuilder().agentId(AgentId.of("different")).build()));
    }

    @Test
    void reconfiguredAgent_stillExecutesSuccessfully() {
        AraRuntime runtime = runtime();
        AgentId id = AgentId.of("still-works");
        AraAgent agent = runtime.createAgent(configFor(id));

        runtime.reconfigureAgent(id, cfg -> cfg.toBuilder().maxIterations(3).build());

        AgentResponse response = agent.execute(AgentTask.of("hello"));
        assertTrue(response.isSuccess(), response.failureReason());
    }

    @Test
    void reconfigureAgent_onContractWrappedAgent_stillWorks() {
        AraRuntime runtime = runtime();
        AgentId id = AgentId.of("with-contract");
        AraAgent agent = runtime.createAgent(configFor(id), io.ara.core.agent.AgentContract.empty());

        assertDoesNotThrow(() -> runtime.reconfigureAgent(id, cfg -> cfg.toBuilder().maxIterations(9).build()));
        assertEquals(9, runtime.agent(id).orElseThrow().config().maxIterations());
        assertTrue(agent.execute(AgentTask.of("hi")).isSuccess());
    }

    /**
     * Regression guard for the {@code AraAgent} → {@code Reconfigurable} split: an agent
     * that never implemented hot reconfiguration (e.g. {@code GraphAgent}, a pipeline
     * agent) must fail with a clear message from {@code reconfigureAgent} itself, not
     * with a {@code NoSuchMethodError} or similar at the call inside it.
     */
    @Test
    void reconfigureAgent_onNonReconfigurableAgent_throwsWithClearMessage() {
        AraRuntime runtime = runtime();
        AgentId id = AgentId.of("not-reconfigurable");
        runtime.registry().register(new MinimalAgent(id));

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> runtime.reconfigureAgent(id, cfg -> cfg));
        assertTrue(ex.getMessage().contains(id.value()),
                "the failure should name the offending agent, not just say 'unsupported'");
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
