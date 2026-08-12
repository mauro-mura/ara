package io.ara.core.agent;

import io.ara.core.llm.LlmProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentConfigToBuilderTest {

    @Test
    void toBuilder_roundTrip_producesAnEqualConfig() {
        AgentConfig original = AgentConfig.defaults()
                .agentType("researcher")
                .systemPrompt("You are a researcher.")
                .primaryLlm(LlmProfile.builder().transportId("gpt-4o").temperature(0.3).build())
                .maxIterations(7)
                .build();

        AgentConfig roundTripped = original.toBuilder().build();

        assertEquals(original, roundTripped);
    }

    @Test
    void toBuilder_thenOverrideOneField_changesOnlyThatField() {
        AgentConfig original = AgentConfig.defaults()
                .agentType("researcher")
                .primaryLlm(LlmProfile.builder().transportId("gpt-4o").temperature(0.3).build())
                .maxIterations(5)
                .build();

        AgentConfig updated = original.toBuilder().maxIterations(9).build();

        assertEquals(9, updated.maxIterations());
        assertEquals(original.agentId(), updated.agentId());
        assertEquals(original.agentType(), updated.agentType());
        assertEquals(original.llmProvider(), updated.llmProvider());
        assertEquals(original.temperature(), updated.temperature());
    }
}
