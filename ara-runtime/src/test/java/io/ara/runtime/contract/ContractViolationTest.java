package io.ara.runtime.contract;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ContractViolation;
import io.ara.core.agent.ContractViolation.Phase;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.runtime.agent.ContractEnforcingAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The structured {@link ContractViolation} that {@code ContractEnforcer} attaches to a refused task. */
class ContractViolationTest {

    private static final AgentId ID = AgentId.of("violation-agent");

    private static final String SCHEMA = """
            {"type":"object","required":["n"],"properties":{"n":{"type":"integer"}}}""";

    private static AraAgent agentAnswering(String answer) {
        return new AraAgent() {
            @Override public AgentId agentId() { return ID; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public void terminate() {}
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), ID, answer, 1, 1, 1,
                        Money.ZERO_EUR, Duration.ofMillis(1), List.of());
            }
        };
    }

    @Test
    void inputSchemaViolation_carriesPhaseAndIssues() {
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(SCHEMA);
        AgentContract contract = AgentContract.builder()
                .inputSchema(validator).addInputProcessor(validator).build();

        AgentResponse r = new ContractEnforcingAgent(agentAnswering("{}"), contract)
                .execute(AgentTask.of("{\"n\":\"x\"}"));

        assertFalse(r.isSuccess());
        ContractViolation v = r.violationOpt().orElseThrow();
        assertEquals(Phase.INPUT, v.phase());
        assertEquals(1, v.issues().size());
        assertEquals("$.n", v.issues().get(0).path());
        assertTrue(r.failureReason().startsWith("Contract input violation: "), r.failureReason());
    }

    @Test
    void outputSchemaViolation_carriesPhaseAndIssues() {
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(SCHEMA);
        AgentContract contract = AgentContract.builder().addOutputProcessor(validator).build();

        AgentResponse r = new ContractEnforcingAgent(agentAnswering("{\"n\":1.5}"), contract)
                .execute(AgentTask.of("go"));

        assertFalse(r.isSuccess());
        ContractViolation v = r.violation();
        assertEquals(Phase.OUTPUT, v.phase());
        assertEquals("$.n", v.issues().get(0).path());
        assertTrue(r.failureReason().startsWith("Contract output violation: "), r.failureReason());
    }

    @Test
    void processorWithOnlyAReason_givesAViolationWithoutIssues() {
        AgentContract contract = AgentContract.builder()
                .addInputProcessor(in -> ProcessingResult.reject("too short"))
                .build();

        AgentResponse r = new ContractEnforcingAgent(agentAnswering("x"), contract).execute(AgentTask.of("hi"));

        ContractViolation v = r.violationOpt().orElseThrow();
        assertEquals(Phase.INPUT, v.phase());
        assertTrue(v.issues().isEmpty());
        assertTrue(r.failureReason().contains("too short"));
    }

    @Test
    void outputViolation_survivingRepair_isStillStructured() {
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(SCHEMA);
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(validator).outputRepairAttempts(2).build();

        AgentResponse r = new ContractEnforcingAgent(agentAnswering("{\"n\":\"no\"}"), contract)
                .execute(AgentTask.of("go"));

        assertEquals(Phase.OUTPUT, r.violation().phase());
        assertEquals("$.n", r.violation().issues().get(0).path());
    }

    @Test
    void agentFailure_isNotAContractViolation() {
        AraAgent failing = new AraAgent() {
            @Override public AgentId agentId() { return ID; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public void terminate() {}
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.failure(task.taskId(), ID, "model down", Duration.ofMillis(1));
            }
        };
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(JsonSchemaValidator.forOutput(SCHEMA)).build();

        AgentResponse r = new ContractEnforcingAgent(failing, contract).execute(AgentTask.of("go"));

        assertFalse(r.isSuccess());
        assertNull(r.violation());
    }

    @Test
    void passingTask_hasNoViolation() {
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(JsonSchemaValidator.forOutput(SCHEMA)).build();

        AgentResponse r = new ContractEnforcingAgent(agentAnswering("{\"n\":3}"), contract)
                .execute(AgentTask.of("go"));

        assertTrue(r.isSuccess());
        assertNull(r.violation());
    }
}
