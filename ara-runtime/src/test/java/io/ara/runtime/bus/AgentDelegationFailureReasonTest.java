package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.common.AgentId;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a delegating agent is told when the delegate does not return an answer. A failed
 * {@code AgentResponse} has empty content and carries its reason in {@code failureReason};
 * a run suspended for an approval has empty content too and no reason at all. Both used to
 * reach the tool as an exception from building the reply message ("content must not be
 * blank"), so the model learned that something failed but never why.
 */
class AgentDelegationFailureReasonTest {

    private static final String CALL = "{\"agent_id\":\"worker\",\"task\":\"do it\"}";

    /** An agent that answers every task with whatever {@code response} builds. */
    private static AraAgent agentAnswering(Function<AgentTask, AgentResponse> response) {
        AgentId id = AgentId.of("worker");
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return AgentConfig.defaults().agentId(id).agentType("t").build(); }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) { return response.apply(task); }
            @Override public void terminate() { }
        };
    }

    private static ToolResult delegateTo(AraAgent worker) {
        AgentRegistry registry = new AgentRegistry();
        registry.register(worker);
        AgentDelegationTool tool = new AgentDelegationTool(new LocalMessageBus(registry), "coordinator");
        return tool.execute(CALL);
    }

    @Test
    void aFailedDelegate_reportsItsReason() {
        ToolResult result = delegateTo(agentAnswering(task -> AgentResponse.failure(
                task.taskId(), AgentId.of("worker"),
                "Session busy: another request is already being processed", Duration.ZERO)));

        assertFalse(result.success());
        assertTrue(result.error().contains("Session busy: another request is already being processed"),
                () -> "the reason must reach the model, got: " + result.error());
    }

    @Test
    void aDelegateSuspendedForApproval_saysSo() {
        ToolResult result = delegateTo(agentAnswering(task -> AgentResponse.awaitingApproval(
                task.taskId(), AgentId.of("worker"), List.of(approvalRequest()), 1, 0, 0,
                io.ara.core.common.Money.ZERO_EUR, Duration.ZERO, List.of())));

        assertFalse(result.success());
        assertTrue(result.error().contains("approval"),
                () -> "the model must learn the delegate is waiting for a person, got: " + result.error());
    }

    private static ApprovalRequest approvalRequest() {
        return new ApprovalRequest("req-1", "worker", "shell_exec", "{}",
                java.time.Instant.now().plus(Duration.ofMinutes(5)), java.util.Map.of());
    }
}
