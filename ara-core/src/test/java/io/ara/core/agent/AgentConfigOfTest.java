package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentConfigOfTest {

    @Test
    void of_setsAgentTypeAndSystemPrompt_leavingEverythingElseAtDefaults() {
        AgentConfig config = AgentConfig.of("translator", "You translate English to Italian.");

        assertEquals("translator", config.agentType());
        assertEquals("You translate English to Italian.", config.systemPrompt());

        // The rest is what AgentConfig.defaults().build() would produce. Asserted field by
        // field rather than by record equality: agentId is generated, so two otherwise
        // identical configs are never equal.
        assertEquals("react", config.plannerStrategy());
        assertEquals(10, config.maxIterations());
        assertEquals(List.of(), config.enabledTools());
        assertEquals("", config.llmProvider());
        assertEquals(0, config.workingMemoryTokenBudget());
    }

    @Test
    void of_withNullSystemPrompt_fallsBackToTheDefaultPrompt() {
        AgentConfig config = AgentConfig.of("translator", null);

        assertEquals("You are a helpful AI agent.", config.systemPrompt());
    }

    @Test
    void of_withNullAgentType_throws() {
        assertThrows(NullPointerException.class, () -> AgentConfig.of(null, "prompt"));
    }

    @Test
    void of_withBlankAgentType_throws() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.of("  ", "prompt"));
    }
}
