package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task a coordinator delegates is work the runtime is doing, so a graceful shutdown has
 * to wait for it. Before the delegate was started through the runtime's own executor it
 * ran on a thread nobody counted: {@code awaitQuiescence} answered "nothing in flight"
 * while a delegate was mid-run, and {@code stop()} could close the executor under it.
 */
class AraRuntimeDelegationQuiescenceTest {

    /** Holds the delegate inside its model call until the test lets it go. */
    private static final class GatedLlmClient implements LlmClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: worker done", 5, 5, "stop", null);
        }

        @Override public String providerId() { return "gated-worker-model"; }
    }

    @Test
    void aDelegateInFlight_isCountedByTheRuntime_untilItFinishes() throws Exception {
        GatedLlmClient workerModel = new GatedLlmClient();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"do the sub-task\"}")
                        .thenFinalAnswer("done")
                        .build())
                .llmClient("worker-model", workerModel)
                .build();
        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model"))
                .plannerStrategy("react").maxIterations(3).build());
        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react").enabledTools(List.of("delegate_task")).maxIterations(3).build());

        // Run the coordinator directly, not through runtime.submit: submit would count the
        // coordinator itself, and this test is about what the delegate adds to the count.
        CompletableFuture<AgentResponse> coordinatorRun =
                CompletableFuture.supplyAsync(() -> coordinator.execute(AgentTask.of("please delegate")));
        assertTrue(workerModel.entered.await(10, TimeUnit.SECONDS), "the delegate never started");

        assertEquals(1, runtime.inFlightTaskCount(), "the delegate is running and must be counted");
        assertFalse(runtime.awaitQuiescence(Duration.ofMillis(100)),
                "the runtime must not report quiescence while a delegate is mid-run");

        workerModel.release.countDown();
        assertTrue(coordinatorRun.get(10, TimeUnit.SECONDS).isSuccess());
        assertTrue(runtime.awaitQuiescence(Duration.ofSeconds(5)));
        assertEquals(0, runtime.inFlightTaskCount());
        runtime.stop();
    }
}
