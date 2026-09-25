package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a workflow run reports as spent — on {@link WorkflowResult#spend()} and, when the workflow
 * is hosted as an agent, on the {@link AgentResponse} — and that it is the real cost of the
 * nodes that ran, not the token counts re-priced with the wrapper agent's own rates.
 */
class WorkflowSpendTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), "EUR");
    }

    /** An agent that reports a fixed real cost — as one bound to a priced model would. */
    private record PricedAgent(AgentId id, Money cost, int inTokens, int outTokens) implements AraAgent {
        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() { return AgentConfig.defaults().agentType("priced").build(); }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            return AgentResponse.success(task.taskId(), id, "done", 1, inTokens, outTokens,
                    cost, Duration.ZERO, List.of());
        }
        @Override public void terminate() { }
    }

    private static PricedAgent priced(String name, String cost) {
        return new PricedAgent(AgentId.of(name), eur(cost), 100, 50);
    }

    @Test
    void aGovernedRun_reportsItsFinalTally_includingAnOpaqueNodesDeclaredCost() {
        WorkflowResult result = Workflow.of()
                .agent("llm", priced("llm", "0.30"))
                .node("tool", in -> "ok").cost("tool", out -> Spend.of(eur("0.20"), 0, 1))
                .edge("llm", "tool")
                .budget(RunBudget.of().maxCost(5.00).build())
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        Spend spend = result.spend().orElseThrow();
        assertEquals(0, eur("0.50").amount().compareTo(spend.money().amount()),
                "0.30 from the agent + 0.20 declared on the opaque node; was " + spend.money());
        assertEquals(150, spend.tokens());
    }

    @Test
    void anUngovernedRun_stillReportsWhatItsAgentNodesCost() {
        WorkflowResult result = Workflow.of()
                .agent("a", priced("a", "0.10"))
                .agent("b", priced("b", "0.15"))
                .edge("a", "b")
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals(0, eur("0.25").amount().compareTo(result.spend().orElseThrow().money().amount()));
    }

    @Test
    void aGraphOfOpaqueNodesWithoutABudget_hasNothingToReport() {
        assertTrue(Workflow.of().node("a", in -> "A").build().run("go", pool).spend().isEmpty());
    }

    @Test
    void reusedRuns_reportTheirOwnSpend_notARunningTotal() {
        Workflow wf = Workflow.of()
                .agent("a", priced("a", "0.10"))
                .budget(RunBudget.of().maxCost(5.00).build())
                .build();

        BigDecimal first = wf.run("1", pool).spend().orElseThrow().money().amount();
        BigDecimal second = wf.run("2", pool).spend().orElseThrow().money().amount();

        assertEquals(0, first.compareTo(second), "each run spent 0.10, not 0.10 then 0.20");
    }

    @Test
    void aWorkflowHostedAsAnAgent_reportsTheRealCost_notTheWrapperRatesPricing() {
        Workflow wf = Workflow.of()
                .agent("a", priced("a", "0.30"))
                .agent("b", priced("b", "0.45"))
                .edge("a", "b")
                .build();

        AgentResponse response = WorkflowAgents.of(wf).execute(AgentTask.of("go"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals(0, eur("0.75").amount().compareTo(response.estimatedCost().amount()),
                "the wrapper agent has no rates of its own (zero) — the cost must come from the nodes; was "
                        + response.estimatedCost());
    }

    @Test
    void aFailedRun_stillReportsWhatWasSpentBeforeItStopped() {
        WorkflowResult result = Workflow.of()
                .agent("a", priced("a", "0.30"))
                .node("boom", in -> { throw new IllegalStateException("boom"); })
                .edge("a", "boom")
                .budget(RunBudget.of().maxCost(5.00).build())
                .build()
                .run("go", pool);

        assertTrue(!result.ok());
        assertEquals(0, eur("0.30").amount().compareTo(result.spend().orElseThrow().money().amount()));
    }
}
