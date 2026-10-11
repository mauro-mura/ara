package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.strategy.PlanPhaseLlmClient.Call;
import io.ara.runtime.strategy.PlanPhaseLlmClient.Phase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A person approves the plan of {@code plan_execute} before any step runs, through the runtime's
 * {@code ApprovalGate}: approved as it is, approved with changes, rejected, or not decided in time.
 */
class PlanExecuteStrategyApprovalTest {

    private static final String TWO_STEPS = "1. Create Person\n2. Create Address";

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A gate that answers each request at once with what {@code decide} says, and keeps the requests. */
    private static final class ScriptedGate implements ApprovalGate {
        final List<ApprovalRequest> requests = new ArrayList<>();
        private final Function<ApprovalRequest, CompletableFuture<ApprovalDecision>> decide;

        ScriptedGate(Function<ApprovalRequest, CompletableFuture<ApprovalDecision>> decide) {
            this.decide = decide;
        }

        static ScriptedGate deciding(ApprovalDecision decision) {
            return new ScriptedGate(request -> CompletableFuture.completedFuture(decision));
        }

        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            requests.add(request);
            return decide.apply(request);
        }

        @Override public void submit(String requestId, ApprovalDecision decision) {
            throw new UnsupportedOperationException("the decision is scripted");
        }

        @Override public List<ApprovalRequest> getPendingRequests() {
            return List.of();
        }
    }

    private static LlmCompletion closed(String argumentsJson) {
        return new LlmCompletion("", 5, 5, "tool_calls",
                "{\"tool_id\":\"close_step\",\"arguments\":" + argumentsJson + "}");
    }

    private static LlmCompletion done() {
        return closed("{\"status\":\"done\"}");
    }

    private static MemoryManager seededMemory() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "build the model");
        return memory;
    }

    private static AgentConfig config(StrategyConfig.PlanExecute strategy) {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(30)
                .strategyConfig(strategy)
                .build();
    }

    private static StrategyConfig.PlanExecute approving(String replanPolicy) {
        return new StrategyConfig.PlanExecute(replanPolicy, 8, 3).withPlanApproval();
    }

    private static ExecutionResult run(PlanPhaseLlmClient llm, ApprovalGate gate, StrategyConfig.PlanExecute strategy) {
        return new PlanExecuteStrategy(gate, Duration.ofMinutes(1)).execute(
                AgentTask.of("build the model"), llm, seededMemory(), ToolRegistry.empty(), config(strategy));
    }

    private static boolean anyMessageContains(Call call, String fragment) {
        return call.messages().stream().anyMatch(message -> message.content().contains(fragment));
    }

    private static PlanPhaseLlmClient planOfTwo() {
        return new PlanPhaseLlmClient().planReplies(TWO_STEPS).stepCompletions(done());
    }

    // ── approved ─────────────────────────────────────────────────────────────

    @Test
    void anApprovedPlan_runsAsItWasMade_andTheGateWasAskedWithTheStepGoals() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Approved());
        PlanPhaseLlmClient llm = planOfTwo();

        ExecutionResult result = run(llm, gate, approving("never"));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(2, llm.callsIn(Phase.STEP).size());
        assertEquals(1, gate.requests.size());
        ApprovalRequest request = gate.requests.get(0);
        assertEquals("approve_plan", request.action());
        assertEquals(List.of("Create Person", "Create Address"), request.payload());
        assertEquals("build the model", request.metadata().get("task"));
    }

    @Test
    void theGateIsAskedBeforeAnyStepRuns() {
        PlanPhaseLlmClient llm = planOfTwo();
        List<Integer> stepCallsWhenAsked = new ArrayList<>();
        ScriptedGate gate = new ScriptedGate(request -> {
            stepCallsWhenAsked.add(llm.callsIn(Phase.STEP).size());
            return CompletableFuture.completedFuture(new ApprovalDecision.Approved());
        });

        run(llm, gate, approving("never"));

        assertEquals(List.of(0), stepCallsWhenAsked, "no step may have run when the person is asked");
    }

    @Test
    void theApprovalIsInTheTrace_betweenThePlanAndTheFirstStep() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Approved());

        ExecutionResult result = run(planOfTwo(), gate, approving("never"));

        List<ExecutionStep> steps = result.steps();
        int asked = indexOfToolCall(steps, "approve_plan");
        assertTrue(asked >= 0, "the request is recorded as an approve_plan call");
        assertEquals("{\"steps\":[\"Create Person\",\"Create Address\"]}", steps.get(asked).arguments());
        assertEquals("Plan approved.", steps.get(asked + 1).content());
        assertTrue(asked < indexOfToolCall(steps, "close_step"), "before the first step closes");
    }

    private static int indexOfToolCall(List<ExecutionStep> steps, String toolId) {
        for (int i = 0; i < steps.size(); i++) {
            if (toolId.equals(steps.get(i).toolId())) return i;
        }
        return -1;
    }

    // ── approved with changes ────────────────────────────────────────────────

    @Test
    void aPlanCorrectedAsAList_isTheOneThatRuns() {
        ScriptedGate gate = ScriptedGate.deciding(
                new ApprovalDecision.Modified(List.of("Create Person", "Create Address", "Write the README")));
        PlanPhaseLlmClient llm = planOfTwo();

        ExecutionResult result = run(llm, gate, approving("never"));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(3, steps.size(), "the person added a step");
        assertTrue(anyMessageContains(steps.get(2), "Write the README"));
    }

    @Test
    void aPlanCorrectedAsText_isReadLikeAPlanTheModelWrote() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Modified(
                "{\"steps\":[{\"id\":\"s1\",\"goal\":\"Only the Person\",\"dependsOn\":[]}]}"));
        PlanPhaseLlmClient llm = planOfTwo();

        ExecutionResult result = run(llm, gate, approving("never"));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(1, steps.size());
        assertTrue(anyMessageContains(steps.get(0), "Only the Person"));
    }

    @Test
    void aCorrectedPlanWithNoStep_endsTheTask_insteadOfRunningNothing() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Modified(List.of(" ", "")));
        PlanPhaseLlmClient llm = planOfTwo();

        ExecutionResult result = run(llm, gate, approving("never"));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReasonOpt().orElse("").contains("no readable step"));
        assertTrue(llm.callsIn(Phase.STEP).isEmpty());
    }

    // ── rejected, or not decided ─────────────────────────────────────────────

    @Test
    void aRejectedPlan_endsTheTask_withTheReason_andNothingRuns() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Rejected("too many steps"));
        PlanPhaseLlmClient llm = planOfTwo();

        ExecutionResult result = run(llm, gate, approving("never"));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReasonOpt().orElse("").contains("too many steps"));
        assertTrue(llm.callsIn(Phase.STEP).isEmpty(), "a rejected plan must not run a single step");
        assertTrue(llm.callsIn(Phase.SYNTHESIS).isEmpty());
    }

    @Test
    void aRejectedPlan_isNotReplanned_evenWhenTheAgentReplansOnFailure() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Rejected("not like this"));
        PlanPhaseLlmClient llm = planOfTwo();

        run(llm, gate, approving("on_failure"));

        assertEquals(1, llm.callsIn(Phase.PLAN).size(), "the person said no: the model is not asked again");
        assertTrue(llm.callsIn(Phase.REPLAN).isEmpty());
        assertEquals(1, gate.requests.size());
    }

    @Test
    void aPlanNobodyDecidesInTime_endsTheTask_insteadOfHanging() {
        ScriptedGate gate = new ScriptedGate(request ->
                CompletableFuture.failedFuture(new ApprovalTimeoutException(request)));
        PlanPhaseLlmClient llm = planOfTwo();

        ExecutionResult result = run(llm, gate, approving("never"));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReasonOpt().orElse("").contains("not approved in time"));
        assertTrue(llm.callsIn(Phase.STEP).isEmpty());
    }

    // ── what is not gated ────────────────────────────────────────────────────

    @Test
    void aPlanThatDoesNotAskForApproval_neverReachesTheGate() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Rejected("would block"));

        ExecutionResult result = run(planOfTwo(), gate, new StrategyConfig.PlanExecute("never", 8, 3));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertTrue(gate.requests.isEmpty(), "a runtime that has a gate does not gate plans nobody asked about");
    }

    @Test
    void aReplan_isNotSubmittedAgain_thePersonApprovedTheFirstPlanOnly() {
        ScriptedGate gate = ScriptedGate.deciding(new ApprovalDecision.Approved());
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .replanReplies("1. Create Person again")
                .stepCompletions(closed("{\"status\":\"failed\",\"reason\":\"disk full\"}"), done());

        ExecutionResult result = run(llm, gate, approving("on_failure"));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(1, llm.callsIn(Phase.REPLAN).size(), "the replan happened");
        assertEquals(1, gate.requests.size(), "and was not put to the person");
    }

    // ── no gate ──────────────────────────────────────────────────────────────

    @Test
    void aConfigThatAsksForApproval_failsAtOnce_whenTheRuntimeHasNoGate() {
        PlanPhaseLlmClient llm = planOfTwo();

        ExecutionResult result = run(llm, null, approving("never"));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReasonOpt().orElse("").contains("ApprovalGate"));
        assertTrue(llm.calls().isEmpty(), "nothing is spent on a plan nobody could approve");
    }
}
