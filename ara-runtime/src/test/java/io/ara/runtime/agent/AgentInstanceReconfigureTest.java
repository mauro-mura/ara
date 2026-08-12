package io.ara.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;

/**
 * Verifies ADR-039's central guarantee at the {@code AgentInstance}/{@code AraAgent}
 * level: a task already in flight finishes with the model it started with; only a
 * session created after {@code reconfigure} sees the new one.
 */
class AgentInstanceReconfigureTest {

    /** Named client whose first {@code complete} call blocks until released. */
    private static final class GatedClient implements LlmClient {
        private final String id;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;

        GatedClient(String id, CountDownLatch release) { this.id = id; this.release = release; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("done-by-" + id, 1, 1, "stop", null);
        }

        @Override public String providerId() { return id; }
    }

    private static AgentConfig configWithModel(AgentId id, String modelId) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(modelId))
                .plannerStrategy("react")
                .maxIterations(3)
                .build();
    }

    @Test
    void sessionPinSurvivesReconfigure_newSessionSeesTheNewModel() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GatedClient modelA = new GatedClient("model-A", release);
        GatedClient modelB = new GatedClient("model-B", new CountDownLatch(0));   // never blocks

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("modelA", modelA)
                .llmClient("modelB", modelB)
                .build();

        AgentId id = AgentId.of("hot-agent");
        AraAgent agent = runtime.createAgent(configWithModel(id, "modelA"));

        AtomicReference<AgentResponse> firstResponse = new AtomicReference<>();
        Thread inFlight = new Thread(() ->
                firstResponse.set(agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1")))));
        inFlight.start();
        assertTrue(modelA.entered.await(5, TimeUnit.SECONDS), "first task must be inside modelA's complete()");

        // Reconfigure to modelB WHILE the first task is still in flight on modelA.
        runtime.reconfigureAgent(id, cfg -> cfg.toBuilder().primaryLlm(LlmProfile.of("modelB")).build());

        release.countDown();
        inFlight.join(5_000);

        assertTrue(firstResponse.get().isSuccess(), firstResponse.get().failureReason());
        assertEquals("modelA/model-A", firstResponse.get().llmProvider(),
                "the in-flight session must finish with the model it started with");

        // A brand-new session, started after reconfigure, must use the new model.
        AgentResponse secondResponse = agent.execute(AgentTask.of("hi again").withSessionId(SessionId.of("s2")));
        assertTrue(secondResponse.isSuccess(), secondResponse.failureReason());
        assertEquals("modelB/model-B", secondResponse.llmProvider());
    }

    @Test
    void reconfigure_withDifferentAgentId_throwsAndLeavesConfigUnchanged() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("modelA", new GatedClient("model-A", new CountDownLatch(0)))
                .build();
        AgentId id = AgentId.of("fixed");
        AraAgent agent = runtime.createAgent(configWithModel(id, "modelA"));

        assertThrows(IllegalArgumentException.class, () ->
                ((Reconfigurable) agent).reconfigure(configWithModel(AgentId.of("other"), "modelA")));
        assertEquals(id, agent.agentId());
    }

    @Test
    void config_reflectsTheNewConfigImmediately_evenBeforeAnyNewSession() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("modelA", new GatedClient("model-A", new CountDownLatch(0)))
                .build();
        AgentId id = AgentId.of("live-config");
        AraAgent agent = runtime.createAgent(configWithModel(id, "modelA"));

        ((Reconfigurable) agent).reconfigure(configWithModel(id, "modelA").toBuilder().maxIterations(11).build());

        assertEquals(11, agent.config().maxIterations());
    }

    @Test
    void reconfigure_onContractWrappedAgent_updatesTheUnderlyingInstance() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("modelA", new GatedClient("model-A", new CountDownLatch(0)))
                .build();
        AgentId id = AgentId.of("contract-wrapped");
        // A non-empty contract forces AgentFactory to wrap the instance in a
        // ContractEnforcingAgent — reconfigure() must delegate through it, not just
        // work for the bare AgentInstance (regression guard for that one-line delegation).
        AgentContract nonEmptyContract = AgentContract.builder()
                .addPromptShaper((prompt, task) -> prompt)
                .build();
        AraAgent agent = runtime.createAgent(configWithModel(id, "modelA"), nonEmptyContract);
        assertTrue(agent instanceof ContractEnforcingAgent);

        ((Reconfigurable) agent).reconfigure(configWithModel(id, "modelA").toBuilder().maxIterations(5).build());

        assertEquals(5, agent.config().maxIterations());
    }
}
