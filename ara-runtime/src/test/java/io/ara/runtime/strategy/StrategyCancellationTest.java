package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies cooperative cancellation (ADR-016): when the executing thread is interrupted
 * — as {@code AgentInstance.terminate(session)} does — every strategy bails out with a
 * "Cancelled" failure instead of running the LLM loop to completion.
 */
class StrategyCancellationTest {

    private final AgentConfig config = AgentConfig.defaults()
            .agentType("t")
            .primaryLlm(LlmProfile.of("stub"))
            .build();

    private static MemoryManager seeded() {
        MemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("system", "You are helpful.");
        m.appendToWorkingMemory("user", "Do something long.");
        return m;
    }

    /** LLM stub that fails the test if it is ever called — cancellation must short-circuit first. */
    private static LlmClient neverCalled() {
        return ScriptedLlmClient.script()
                .thenFinalAnswer("should not be reached")
                .build();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();   // ensure the flag never leaks to the next test on a reused thread
    }

    private void assertCancelled(ExecutionResult result) {
        assertFalse(result.isSuccess(), "cancelled run must not succeed");
        assertTrue(result.failureReason() != null && result.failureReason().contains("Cancelled"),
                "expected a 'Cancelled' failure, got: " + result.failureReason());
    }

    @Test
    void reactStrategy_cancelledWhenInterrupted() {
        Thread.currentThread().interrupt();
        ExecutionResult r = new ReactStrategy()
                .execute(AgentTask.of("hi"), neverCalled(), seeded(), ToolRegistry.empty(), config);
        assertCancelled(r);
    }

    @Test
    void planExecuteStrategy_cancelledWhenInterrupted() {
        Thread.currentThread().interrupt();
        ExecutionResult r = new PlanExecuteStrategy()
                .execute(AgentTask.of("hi"), neverCalled(), seeded(), ToolRegistry.empty(), config);
        assertCancelled(r);
    }

    @Test
    void reflexionStrategy_cancelledWhenInterrupted() {
        Thread.currentThread().interrupt();
        // Real React delegate: it also observes the interrupt, and Reflexion must NOT
        // turn the cancellation into a reflection + retry.
        ExecutionStrategy delegate = new ReactStrategy();
        ExecutionResult r = new ReflexionStrategy(delegate)
                .execute(AgentTask.of("hi"), neverCalled(), seeded(), ToolRegistry.empty(), config);
        assertCancelled(r);
    }
}
