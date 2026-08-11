package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.agent.InstanceContextStore;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code AraRuntime.replaceAgent(...)} / {@code AgentFactory.replace(...)} —
 * the atomic, non-throwing alternative to {@code createAgent(...)} for swapping in a
 * new agent under an id that may already be registered, without terminating whatever
 * was there before.
 */
class AraRuntimeReplaceAgentTest {

    private static AgentConfig configFor(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("react")
                .build();
    }

    @Test
    void replaceAgent_withFreshId_behavesLikeCreateAgent() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();

        AraAgent agent = runtime.replaceAgent(configFor(AgentId.of("fresh")));

        assertNotNull(agent);
        assertSame(agent, runtime.agent(AgentId.of("fresh")).orElseThrow());
    }

    @Test
    void replaceAgent_withExistingId_neverThrows_andInstallsTheNewAgent() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenFinalAnswer("first")
                        .thenFinalAnswer("second")
                        .build())
                .build();

        AgentId id = AgentId.of("dup");
        AraAgent original = runtime.createAgent(configFor(id));

        AraAgent replacement = assertDoesNotThrow(() -> runtime.replaceAgent(configFor(id)));

        assertNotSame(original, replacement);
        assertSame(replacement, runtime.agent(id).orElseThrow(),
                "the registry must resolve this id to the new agent going forward");
    }

    @Test
    void replaceAgent_doesNotTerminateTheDisplacedAgent() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("still works after replace").build())
                .build();

        AgentId id = AgentId.of("survivor");
        AraAgent original = runtime.createAgent(configFor(id));
        runtime.replaceAgent(configFor(id));

        // The caller's own reference to the displaced agent must still be fully
        // functional — replaceAgent() must never call terminate() on it. A terminated
        // AgentInstance fails fast with "Agent terminated" instead of running the
        // strategy at all, so a real, scripted answer coming back proves it wasn't.
        AgentResponse response = original.execute(AgentTask.of("still there?"));
        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals("still works after replace", response.content());
    }

    @Test
    void replaceAgent_preservesInstanceContextStoreEntry_unlikeDestroyAgent() {
        InstanceContextStore store = new InstanceContextStore();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .instanceContextStore(store)
                .build();

        AgentId id = AgentId.of("ctx-agent");
        store.set(id, Map.of("secret", "s1"));
        runtime.createAgent(configFor(id));

        runtime.replaceAgent(configFor(id));

        assertEquals("s1", store.forAgent(id).get("secret"),
                "replaceAgent keeps the same AgentId, so its instance context must survive — "
                + "unlike destroyAgent, which explicitly clears it");
    }

    @Test
    void replaceAgent_withContract_alsoNeverThrows() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenFinalAnswer("a")
                        .thenFinalAnswer("b")
                        .build())
                .build();

        AgentId id = AgentId.of("with-contract");
        runtime.createAgent(configFor(id));

        AraAgent replacement = assertDoesNotThrow(() ->
                runtime.replaceAgent(configFor(id), io.ara.core.agent.AgentContract.empty()));

        assertSame(replacement, runtime.agent(id).orElseThrow());
    }
}
