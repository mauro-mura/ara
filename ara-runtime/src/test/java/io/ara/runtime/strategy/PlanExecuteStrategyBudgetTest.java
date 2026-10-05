package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@code ReactStrategyTest}'s D-03 budget coverage for {@link PlanExecuteStrategy}.
 *
 * <p>Before the fix, {@code PlanExecuteStrategy} was the only ReAct-family strategy that
 * never called {@link ReactExecutionSupport#checkBudget} / {@link
 * ReactExecutionSupport#chargeRunBudget} — every other strategy's loop ({@code ReActLoop},
 * {@code ReflActStrategy}) enforces both governors around each LLM call. A plan-execute run
 * could therefore spend unbounded cost on every call: planning, each step round, synthesis
 * and replan are four independent LLM call sites, none of them metered.
 */
class PlanExecuteStrategyBudgetTest {

    /** 500 in + 500 out per call at 1+2 EUR/1k = 1.50 EUR per call. */
    private static final int CALLS_BEFORE_LIMIT = 2;

    @Test
    void costBudget_stopsTheRunOnceExceeded() {
        // 500 in + 500 out per call at 1+2 EUR/1k = 1.50 EUR per call, cap 2.00 EUR:
        // the third call would breach, so the run must fail at or before it —
        // never complete successfully after spending more than the cap.
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.of("1.0", "EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("2.0", "EUR"))
                        .costBudget(io.ara.core.common.Budget.limited(io.ara.core.common.Money.of("2.0", "EUR")))
                        .build())
                .maxTokensPerStep(1000)
                .maxIterations(10)
                .build();

        LlmCompletion expensive = new LlmCompletion("1. Do step one", 500, 500, "stop", null);
        CountingClient llm = new CountingClient(expensive, "Final answer.");

        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "do the thing");

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("do the thing"), llm, memory, ToolRegistry.empty(), config);

        assertFalse(result.isSuccess(),
                "a plan_execute run over its cost budget must fail, got: " + result.failureReason());
        assertTrue(llm.calls.get() <= CALLS_BEFORE_LIMIT + 1,
                "the run must stop promptly once over budget, made " + llm.calls.get() + " calls");
    }

    @Test
    void unlimitedBudget_neverBlocks() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.of("1000.0", "EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("1000.0", "EUR"))
                        .costBudget(io.ara.core.common.Budget.unlimited())
                        .build())
                .maxIterations(10)
                .build();

        LlmCompletion expensive = new LlmCompletion("1. Do step one", 500, 500, "stop", null);
        CountingClient llm = new CountingClient(expensive, "Final answer.");

        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "do the thing");

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("do the thing"), llm, memory, ToolRegistry.empty(), config);

        assertTrue(result.isSuccess(), result.failureReason());
    }

    /** Counts LLM calls; replies with the scripted completion until exhausted, then final answer. */
    private static final class CountingClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        private final LlmCompletion scripted;
        private final String fallbackAnswer;

        CountingClient(LlmCompletion scripted, String fallbackAnswer) {
            this.scripted = scripted;
            this.fallbackAnswer = fallbackAnswer;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            if (calls.incrementAndGet() == 1) {
                return scripted;   // planning: produces a one-step plan
            }
            return new LlmCompletion(fallbackAnswer, 500, 500, "stop", null);
        }

        @Override public String providerId() { return "counting-stub"; }
    }
}