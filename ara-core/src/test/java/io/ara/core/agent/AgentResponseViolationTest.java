package io.ara.core.agent;

import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentResponseViolationTest {

    private static final AgentId AGENT = AgentId.of("a");

    private static AgentResponse thirteenArg() {
        return new AgentResponse("t", AGENT, "", AgentState.FAILED, 0, 0, 0, Money.ZERO_EUR,
                Duration.ZERO, "nope", Instant.now(), List.of(), null);
    }

    @Test
    void thirteenArgConstructor_leavesViolationNull() {
        AgentResponse r = thirteenArg();
        assertNull(r.violation());
        assertTrue(r.violationOpt().isEmpty());
    }

    @Test
    void factories_leaveViolationNull() {
        assertNull(AgentResponse.failure("t", AGENT, "x", Duration.ZERO).violation());
        assertNull(AgentResponse.success("t", AGENT, "ok", 1, 1, 1, Money.ZERO_EUR, Duration.ZERO, List.of())
                .violation());
    }

    @Test
    void withViolation_setsIt_andKeepsEverythingElse() {
        ContractViolation v = new ContractViolation(ContractViolation.Phase.INPUT,
                List.of(new ProcessingResult.Issue("$.a", "bad")));

        AgentResponse r = thirteenArg().withViolation(v);

        assertSame(v, r.violation());
        assertEquals("nope", r.failureReason());
        assertEquals(AgentState.FAILED, r.finalState());
    }

    @Test
    void withContent_withCost_withLlmProvider_keepTheViolation() {
        ContractViolation v = new ContractViolation(ContractViolation.Phase.OUTPUT, List.of());
        AgentResponse r = thirteenArg().withViolation(v);

        assertSame(v, r.withContent("x").violation());
        assertSame(v, r.withCost(Money.ZERO_EUR).violation());
        assertSame(v, r.withLlmProvider("p").violation());
    }

    @Test
    void phase_saysWhoseFaultItIs() {
        assertTrue(ContractViolation.Phase.INPUT.callerFault());
        assertTrue(ContractViolation.Phase.MEDIA.callerFault());
        assertFalse(ContractViolation.Phase.OUTPUT.callerFault());
    }

    @Test
    void contractViolation_copiesIssues_andRejectsNullPhase() {
        assertTrue(new ContractViolation(ContractViolation.Phase.INPUT, null).issues().isEmpty());
        assertThrows(NullPointerException.class, () -> new ContractViolation(null, List.of()));
    }

    @Test
    void reject_oneArg_hasNoIssues_andTwoArgKeepsThem() {
        assertTrue(new ProcessingResult.Reject("why").issues().isEmpty());
        assertTrue(new ProcessingResult.Reject("why", null).issues().isEmpty());

        ProcessingResult.Reject r = (ProcessingResult.Reject) ProcessingResult.reject("why",
                List.of(new ProcessingResult.Issue("$", "m")));
        assertEquals(1, r.issues().size());
        assertEquals("why", r.reason());
    }
}
