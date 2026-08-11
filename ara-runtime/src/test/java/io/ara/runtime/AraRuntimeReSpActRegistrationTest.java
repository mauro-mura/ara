package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Confirms {@code "respact"} is wired into {@code AraRuntime}'s default {@code
 * ExecutionPlanner} (alongside {@code react}/{@code plan_execute}/{@code reflexion}) —
 * an end-to-end check that the registration in {@code AraRuntime.buildExecutionPlanner}
 * actually resolves, not just that {@code ReSpActStrategy} compiles in isolation.
 */
class AraRuntimeReSpActRegistrationTest {

    @Test
    void respactStrategy_isResolvableByName_throughAraRuntime() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(new LlmCompletion("Action: SPEAK\nMessage: Got it, one sec.", 10, 10, "stop", null))
                .build();

        AraRuntime runtime = AraRuntime.builder().llmClient(llm).build();
        try {
            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentType("assistant")
                    .primaryLlm(LlmProfile.of("stub"))
                    .plannerStrategy("respact")
                    .build());

            AgentResponse response = agent.execute(AgentTask.of("hello"));

            assertTrue(response.isSuccess(), () -> "expected success, got: " + response.failureReason());
            assertEquals("Got it, one sec.", response.content());
        } finally {
            runtime.stop();
        }
    }
}
