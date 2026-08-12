package io.ara.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.stubs.ScriptedLlmClient;

/**
 * Confirms {@code "reflact"} is wired into {@code AraRuntime}'s default {@code
 * ExecutionPlanner} alongside {@code react}/{@code respact}/{@code plan_execute}/{@code
 * reflexion} — an end-to-end check that the registration in {@code
 * AraRuntime.buildExecutionPlanner} actually resolves, not just that {@code
 * ReflActStrategy} compiles in isolation.
 */
class AraRuntimeReflActRegistrationTest {

    @Test
    void reflactStrategy_isResolvableByName_throughAraRuntime() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("hello there")
                .build();

        AraRuntime runtime = AraRuntime.builder().llmClient(llm).build();
        try {
            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentType("assistant")
                    .primaryLlm(LlmProfile.of("stub"))
                    .plannerStrategy("reflact")
                    .build());

            AgentResponse response = agent.execute(AgentTask.of("hi"));

            assertTrue(response.isSuccess(), () -> "expected success, got: " + response.failureReason());
            assertEquals("hello there", response.content());
        } finally {
            runtime.stop();
        }
    }
}
