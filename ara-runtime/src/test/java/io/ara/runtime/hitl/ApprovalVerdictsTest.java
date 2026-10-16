package io.ara.runtime.hitl;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.PendingApproval;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalVerdictsTest {

    private static final String ARGS = "{\"id\":1}";

    private static ApprovalRequest request(String tool) {
        return ApprovalRequest.of("agent", tool, ARGS, Duration.ofMinutes(1));
    }

    private static final AgentTask TASK = AgentTask.of("go").withTaskId("task-1");

    @Test
    void aVerdict_isTakenOnce_soTheNextIdenticalCallIsAskedAgain() {
        ApprovalRequest asked = request("pay");
        ApprovalVerdicts verdicts = ApprovalVerdicts.of("task-1",
                new PendingApproval(List.of(new PendingApproval.ToolCall("pay", ARGS, null, asked))),
                Map.of(asked.requestId(), new ApprovalDecision.Approved()));

        assertTrue(verdicts.holds(TASK, "pay", ARGS));
        assertTrue(verdicts.holds(TASK, "pay", ARGS), "asking does not use it up");
        assertEquals(new ApprovalDecision.Approved(), verdicts.take(TASK, "pay", ARGS).orElseThrow());
        assertFalse(verdicts.holds(TASK, "pay", ARGS));
        assertTrue(verdicts.take(TASK, "pay", ARGS).isEmpty());
    }

    @Test
    void aVerdict_isNotHeldForDifferentArgumentsOrAnotherTool() {
        ApprovalRequest asked = request("pay");
        ApprovalVerdicts verdicts = ApprovalVerdicts.of("task-1",
                new PendingApproval(List.of(new PendingApproval.ToolCall("pay", ARGS, null, asked))),
                Map.of(asked.requestId(), new ApprovalDecision.Approved()));

        assertFalse(verdicts.holds(TASK, "pay", "{\"id\":2}"));
        assertFalse(verdicts.holds(TASK, "refund", ARGS));
    }

    @Test
    void aDelegatedTask_cannotUseItsCallersVerdicts() {
        ApprovalRequest asked = request("pay");
        ApprovalVerdicts verdicts = ApprovalVerdicts.of("task-1",
                new PendingApproval(List.of(new PendingApproval.ToolCall("pay", ARGS, null, asked))),
                Map.of(asked.requestId(), new ApprovalDecision.Approved()));
        AgentTask delegate = AgentTask.of("sub").withTaskId("delegate-task");

        assertFalse(verdicts.holds(delegate, "pay", ARGS));
        assertTrue(verdicts.take(delegate, "pay", ARGS).isEmpty());
        assertTrue(verdicts.holds(TASK, "pay", ARGS), "and the caller's own verdict is still there");
    }

    @Test
    void twoIdenticalCallsInOneTurn_holdTwoVerdicts() {
        ApprovalRequest first = request("pay");
        ApprovalRequest second = request("pay");
        ApprovalVerdicts verdicts = ApprovalVerdicts.of("task-1",
                new PendingApproval(List.of(
                        new PendingApproval.ToolCall("pay", ARGS, "c1", first),
                        new PendingApproval.ToolCall("pay", ARGS, "c2", second))),
                Map.of(first.requestId(), new ApprovalDecision.Approved(),
                        second.requestId(), new ApprovalDecision.Rejected("no")));

        assertTrue(verdicts.take(TASK, "pay", ARGS).isPresent());
        assertTrue(verdicts.take(TASK, "pay", ARGS).isPresent());
        assertTrue(verdicts.take(TASK, "pay", ARGS).isEmpty());
    }

    @Test
    void aCallThatNeededNoApproval_getsNoVerdict_andAMissingDecisionIsRefused() {
        ApprovalRequest asked = request("pay");
        PendingApproval pending = new PendingApproval(List.of(
                new PendingApproval.ToolCall("read", ARGS, null, null),
                new PendingApproval.ToolCall("pay", ARGS, null, asked)));

        assertFalse(ApprovalVerdicts.of("task-1", pending, Map.of(asked.requestId(), new ApprovalDecision.Approved()))
                .holds(TASK, "read", ARGS));
        assertThrows(IllegalArgumentException.class, () -> ApprovalVerdicts.of("task-1", pending, Map.of()));
    }
}
