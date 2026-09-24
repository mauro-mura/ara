package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.budget.RunBudget;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D2 — agent-shaped nodes on the generic {@link Workflow} facade: a node runs a
 * real {@link AraAgent} and its {@link AgentResponse} is captured, so token/cost reach the
 * journal and {@link WorkflowResult} instead of vanishing into a plain function body.
 */
class WorkflowAgentNodeTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void agentNode_runsTheAgent_andCapturesItsResponse() {
        StubAgent stub = new StubAgent("hello", 7, 5);

        WorkflowResult result = Workflow.of()
                .agent("a", stub)
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        NodeOutcome.Completed completed = (NodeOutcome.Completed) result.firstOf("a").outcome();
        assertEquals("hello", completed.content());
        assertEquals("go", stub.seen.get(0).input(), "the composed input is the agent's task input");

        assertNotNull(completed.response(), "an agent-shaped node carries its AgentResponse in the journal");
        assertEquals(7, completed.response().inputTokens());
        assertEquals(5, completed.response().outputTokens());
    }

    @Test
    void agentNode_totalsAreSummedAcrossNodes() {
        WorkflowResult result = Workflow.of()
                .agent("a", new StubAgent("A", 7, 5))
                .agent("b", new StubAgent("B", 3, 2))
                .edge("a", "b")
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals(10, result.totalPromptTokens());
        assertEquals(7, result.totalOutputTokens());
        assertTrue(result.totalSpend().isPresent());
        assertEquals(17, result.totalSpend().orElseThrow().tokens());
    }

    @Test
    void agentNode_chargesTheBudgetFromItsResponse_notFromACostFunction() {
        WorkflowResult result = Workflow.of()
                .agent("a", new StubAgent("A", 80_000, 80_000))
                .budget(RunBudget.of().maxTokens(100_000).build())
                .build()
                .run("go", pool);

        assertFalse(result.ok(), result.failureReason());
        assertTrue(result.failureReason().contains("TOKENS"), result.failureReason());
        assertTrue(result.failureReason().contains("node a#0"), result.failureReason());
    }

    @Test
    void agentNode_failedResponse_failsTheNode() {
        StubAgent failing = new StubAgent("ignored", 0, 0, true);

        WorkflowResult result = Workflow.of()
                .agent("a", failing)
                .build()
                .run("go", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("node 'a'"), result.failureReason());
        assertTrue(result.failureReason().contains("stub-failure"), result.failureReason());
    }

    @Test
    void agentNode_taskShaper_isHonored() {
        StubAgent stub = new StubAgent("out", 0, 0);

        Workflow.of()
                .agent("a", stub, input -> AgentTask.of("shaped:" + input))
                .build()
                .run("go", pool);

        assertEquals("shaped:go", stub.seen.get(0).input());
    }

    @Test
    void opaqueNode_hasNoResponse_andReportsZeroTokens() {
        WorkflowResult result = Workflow.of()
                .node("a", in -> "plain")
                .build()
                .run("go", pool);

        NodeOutcome.Completed completed = (NodeOutcome.Completed) result.firstOf("a").outcome();
        assertNull(completed.response());
        assertEquals(0, result.totalPromptTokens());
        assertEquals(0, result.totalOutputTokens());
        assertTrue(result.totalSpend().isEmpty());
    }

    @Test
    void workflowStrategy_reportsAgentTokens() {
        AraAgent agent = WorkflowAgents.of(Workflow.of()
                .agent("a", new StubAgent("A", 11, 4))
                .build());

        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertTrue(response.isSuccess());
        assertEquals(11, response.inputTokens());
        assertEquals(4, response.outputTokens());
    }

    @Test
    void agentNode_preservesAgentBindingThroughWithMethods() {
        StubAgent stub = new StubAgent("A", 0, 0);
        WorkflowNode node = WorkflowNode.agent("a", new AgentBinding(stub, null))
                .withOnUncertainResume(UncertainResumePolicy.FAIL);

        assertNotNull(node.agent());
        assertEquals(stub, node.agent().agent());
    }

    @Test
    void builderAgent_rejectsNullAgent() {
        assertThrows(NullPointerException.class, () -> Workflow.of().agent("a", null));
    }

    // ── test double: a hand-written AraAgent, no mocking framework ──────────────

    private static final class StubAgent implements AraAgent {

        private final AgentId id = AgentId.generate();
        private final String content;
        private final int inputTokens;
        private final int outputTokens;
        private final boolean fail;
        final List<AgentTask> seen = new ArrayList<>();

        StubAgent(String content, int inputTokens, int outputTokens) {
            this(content, inputTokens, outputTokens, false);
        }

        StubAgent(String content, int inputTokens, int outputTokens, boolean fail) {
            this.content = content;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.fail = fail;
        }

        @Override public AgentId agentId() { return id; }

        @Override public AgentConfig config() { return AgentConfig.defaults().agentType("stub").build(); }

        @Override public AgentState currentState() { return AgentState.IDLE; }

        @Override
        public AgentResponse execute(AgentTask task) {
            seen.add(task);
            if (fail) {
                return AgentResponse.failure(task.taskId(), id, "stub-failure", Duration.ZERO);
            }
            return AgentResponse.success(task.taskId(), id, content, 1, inputTokens, outputTokens,
                    Money.ZERO_EUR, Duration.ZERO, List.of());
        }

        @Override public void terminate() { }
    }
}
