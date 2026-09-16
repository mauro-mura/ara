package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-fast selection for {@link ExecutionPlanner} (see {@code strategy/README.md}):
 * an unregistered {@code plannerStrategy} name is an error, never a silent fallback
 * to another strategy.
 */
class ExecutionPlannerTest {

    private record MarkerStrategy(String name) implements ExecutionStrategy {
        @Override
        public ExecutionResult execute(AgentTask task, io.ara.core.llm.LlmClient llm,
                                       io.ara.core.memory.MemoryManager memory,
                                       io.ara.core.tool.ToolRegistry tools, AgentConfig config) {
            return ExecutionResult.success("ran:" + name, 1, 0);
        }
        @Override public String strategyName() { return name; }
    }

    private static AgentConfig configWith(String strategy) {
        return AgentConfig.defaults().agentId(AgentId.of("t")).agentType("t")
                .plannerStrategy(strategy).build();
    }

    @Test
    void registeredName_selectsTheStrategy() {
        ExecutionPlanner planner = ExecutionPlanner.builder()
                .register(new MarkerStrategy("alpha")).build();

        assertEquals("alpha", planner.select(configWith("alpha")).strategyName());
    }

    @Test
    void unregisteredName_throwsAndListsRegisteredStrategies() {
        ExecutionPlanner planner = ExecutionPlanner.builder()
                .register(new MarkerStrategy("alpha"))
                .register(new MarkerStrategy("beta")).build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> planner.select(configWith("typo")));
        assertTrue(ex.getMessage().contains("typo"), () -> "names the offender: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("alpha"), () -> "lists registered names: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("beta"), () -> "lists registered names: " + ex.getMessage());
    }

    @Test
    void anAgentConfigLeftAtItsOwnReactDefault_mustResolveToARealRegistration() {
        // AgentConfig's plannerStrategy defaults to "react" and AraRuntime always
        // registers it — but a planner without that registration must fail loudly,
        // not reassign the run to whatever happens to be registered.
        ExecutionPlanner planner = ExecutionPlanner.builder()
                .register(new MarkerStrategy("other")).build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> planner.select(configWith(ExecutionPlanner.DEFAULT_STRATEGY)));
        assertTrue(ex.getMessage().contains(ExecutionPlanner.DEFAULT_STRATEGY),
                () -> "names the missing default: " + ex.getMessage());
    }

    @Test
    void hasStrategy_reportsRegistration() {
        ExecutionPlanner planner = ExecutionPlanner.builder()
                .register(new MarkerStrategy("alpha")).build();

        assertTrue(planner.hasStrategy("alpha"));
        assertFalse(planner.hasStrategy("missing"));
    }

    @Test
    void buildingWithNoStrategy_isRejected() {
        assertThrows(IllegalStateException.class,
                () -> ExecutionPlanner.builder().build());
        assertDoesNotThrow(() -> ExecutionPlanner.builder()
                .register(new MarkerStrategy("alpha")).build());
    }
}