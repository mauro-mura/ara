package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code AraRuntime.updateSharedModel(...)} (ADR-039 §4-5): fleet-wide
 * administration of a shared LLM transport, distinct from per-agent {@code
 * reconfigureAgent}. {@code updateSharedModel} never disturbs in-flight sessions;
 * {@code forceUpdateSharedModel} evicts them via the existing cooperative-cancel path.
 */
class AraRuntimeUpdateSharedModelTest {

    /** Named client whose first {@code complete} call blocks until released or interrupted. */
    private static final class GatedClient implements LlmClient {
        private final String id;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;
        private final AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        GatedClient(String id, CountDownLatch release) { this.id = id; this.release = release; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("done-by-" + id, 1, 1, "stop", null);
        }

        @Override public String providerId() { return id; }
    }

    private static AgentConfig configFor(AgentId id, String modelId) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(modelId))
                .plannerStrategy("react")
                .maxIterations(3)
                .build();
    }

    @Test
    void forceUpdate_evictsSessionsStillPinnedToTheRetiredTransport() throws Exception {
        CountDownLatch release = new CountDownLatch(1);   // only released manually if not interrupted first
        GatedClient modelA = new GatedClient("model-A", release);

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("modelA", modelA)
                .llmClientFactory(profile -> modelA)   // never actually invoked in this test; required by build()
                .build();

        AgentId id = AgentId.of("evictable");
        AraAgent agent = runtime.createAgent(configFor(id, "modelA"));

        Thread inFlight = new Thread(() -> agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1"))));
        inFlight.start();
        assertTrue(modelA.entered.await(5, TimeUnit.SECONDS), "task must be inside modelA's complete()");

        runtime.forceUpdateSharedModel("modelA", new LlmTransport("http://any", null, "modelA-v2"));

        // The session's thread must be interrupted promptly by the forced drain.
        long deadline = System.currentTimeMillis() + 5_000;
        while (!modelA.wasInterrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(modelA.wasInterrupted.get(), "forceUpdateSharedModel must evict (interrupt) the session pinned to the retired transport");

        release.countDown();
        inFlight.join(5_000);
    }

    @Test
    void updateSharedModel_neverInterruptsInFlightSessions() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GatedClient modelA = new GatedClient("model-A", release);

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("modelA", modelA)
                .llmClientFactory(profile -> modelA)
                .build();

        AgentId id = AgentId.of("not-evicted");
        AraAgent agent = runtime.createAgent(configFor(id, "modelA"));

        Thread inFlight = new Thread(() -> agent.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1"))));
        inFlight.start();
        assertTrue(modelA.entered.await(5, TimeUnit.SECONDS));

        runtime.updateSharedModel("modelA", new LlmTransport("http://any", null, "modelA-v2"));

        Thread.sleep(200);
        assertFalse(modelA.wasInterrupted.get(), "updateSharedModel must never disturb an in-flight session");

        release.countDown();
        inFlight.join(5_000);
    }
}
