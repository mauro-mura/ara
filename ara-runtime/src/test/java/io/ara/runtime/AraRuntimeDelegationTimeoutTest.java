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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code AraRuntime.Builder.delegationTimeout(...)} actually reaches the
 * {@code delegate_task} tool every created agent gets wired with — before this option
 * existed, {@code AgentDelegationTool.DEFAULT_TIMEOUT_SEC} (120s) was hardcoded in
 * {@code AraRuntime.Builder.buildAgentFactory}, with no way to change it short of
 * bypassing {@code AraRuntime} and assembling the tool registry by hand.
 */
class AraRuntimeDelegationTimeoutTest {

    /** Never returns within any reasonable test timeout — simulates a slow delegate. */
    private static final class HangingLlmClient implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: too late", 5, 5, "stop", null);
        }
        @Override public String providerId() { return "hanging-worker-model"; }
    }

    @Test
    void delegationTimeout_configuredOnTheBuilder_boundsTheDelegateCallInsteadOfTheDefault120s() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"do the sub-task\"}")
                        .thenFinalAnswer("done")
                        .build())
                .llmClient("worker-model", new HangingLlmClient())
                // Far below both the 10s worker hang and the 120s framework default —
                // proves this value, not the default, is what bounds the delegation.
                .delegationTimeout(Duration.ofMillis(300))
                .build();

        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model"))
                .plannerStrategy("react")
                .maxIterations(3)
                .executionTimeout(Duration.ofSeconds(30))
                .build());

        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task"))
                .maxIterations(3)
                .build());

        Instant start = Instant.now();
        AgentResponse response = coordinator.execute(AgentTask.of("please delegate"));
        long elapsedMs = Duration.between(start, Instant.now()).toMillis();

        assertTrue(response.isSuccess(), response.failureReason());
        assertTrue(response.content().contains("done"));
        // The delegate_task tool call must have failed with a timeout observation for the
        // coordinator to still reach "done" via its own scripted final answer — this response
        // alone doesn't prove *which* timeout fired, so the elapsed-time bound below is what
        // actually distinguishes 300ms from the 120s default.
        assertTrue(elapsedMs < 5000,
                "coordinator.execute() took " + elapsedMs
                        + "ms — the 300ms delegationTimeout should have bounded the delegate_task "
                        + "call long before the worker's 10s hang or the 120s framework default");
    }
}
