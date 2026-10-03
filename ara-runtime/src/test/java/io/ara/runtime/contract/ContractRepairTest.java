package io.ara.runtime.contract;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.runtime.agent.ContractEnforcingAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AgentContract#outputRepairAttempts()} as applied by {@code ContractEnforcer}. */
class ContractRepairTest {

    private static final AgentId ID = AgentId.of("repair-agent");

    private static final String SCHEMA = """
            {"type":"object","required":["total"],"properties":{"total":{"type":"number"}}}""";

    private static AgentContract schemaContract(int repairs) {
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(SCHEMA);
        return AgentContract.builder()
                .outputSchema(validator)
                .addOutputProcessor(validator)
                .outputRepairAttempts(repairs)
                .build();
    }

    @Test
    void defaultIsZero_andRejectionFailsAtOnce() {
        ScriptedAgent inner = new ScriptedAgent("{\"total\":\"ten\"}", "{\"total\":10}");
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(JsonSchemaValidator.forOutput(SCHEMA))
                .build();
        assertEquals(0, contract.outputRepairAttempts());

        AgentResponse r = new ContractEnforcingAgent(inner, contract).execute(AgentTask.of("sum"));

        assertFalse(r.isSuccess());
        assertTrue(r.failureReason().startsWith("Contract output violation: Schema violations"), r.failureReason());
        assertEquals(1, inner.inputs.size(), "no repair attempt without opting in");
    }

    @Test
    void rejectedAnswer_isRepaired_onTheNextAttempt() {
        ScriptedAgent inner = new ScriptedAgent("{\"total\":\"ten\"}", "{\"total\":10}");

        AgentResponse r = new ContractEnforcingAgent(inner, schemaContract(2)).execute(AgentTask.of("sum 4 and 6"));

        assertTrue(r.isSuccess(), r.failureReason());
        assertEquals("{\"total\":10}", r.content());
        assertEquals(2, inner.inputs.size());
    }

    @Test
    void repairInput_carriesOriginalRequest_rejectedAnswer_andReason() {
        ScriptedAgent inner = new ScriptedAgent("{\"total\":\"ten\"}", "{\"total\":10}");

        new ContractEnforcingAgent(inner, schemaContract(1)).execute(AgentTask.of("sum 4 and 6"));

        String repair = inner.inputs.get(1);
        assertTrue(repair.startsWith("sum 4 and 6"), repair);
        assertTrue(repair.contains("{\"total\":\"ten\"}"), "rejected answer quoted back: " + repair);
        assertTrue(repair.contains("$.total"), "rejection reason with its path: " + repair);
    }

    @Test
    void repairAttempt_reusesTheShapedTask() {
        ScriptedAgent inner = new ScriptedAgent("{}", "{\"total\":1}");

        new ContractEnforcingAgent(inner, schemaContract(1)).execute(AgentTask.of("x"));

        // outputSchema puts the schema in the hints; the repair attempt must keep it
        assertNotNull(inner.tasks.get(1).hints());
        assertEquals(inner.tasks.get(0).hints(), inner.tasks.get(1).hints());
        assertEquals(inner.tasks.get(0).taskId(), inner.tasks.get(1).taskId());
    }

    @Test
    void attemptsExhausted_failWithTheLastReason() {
        ScriptedAgent inner = new ScriptedAgent("{}", "{\"total\":\"a\"}", "{\"total\":\"b\"}", "{\"total\":3}");

        AgentResponse r = new ContractEnforcingAgent(inner, schemaContract(2)).execute(AgentTask.of("x"));

        assertFalse(r.isSuccess());
        assertEquals(3, inner.inputs.size(), "1 attempt + 2 repairs, never a 4th");
        assertTrue(r.failureReason().contains("$.total"), r.failureReason());
        assertTrue(inner.inputs.get(2).contains("{\"total\":\"a\"}"),
                "each repair quotes the answer it is repairing");
    }

    @Test
    void usage_accumulatesAcrossAttempts() {
        ScriptedAgent inner = new ScriptedAgent("{}", "{}", "{\"total\":1}");

        AgentResponse r = new ContractEnforcingAgent(inner, schemaContract(3)).execute(AgentTask.of("x"));

        assertTrue(r.isSuccess());
        assertEquals(3 * ScriptedAgent.ITERATIONS, r.iterationsUsed());
        assertEquals(3 * ScriptedAgent.IN_TOKENS, r.inputTokens());
        assertEquals(3 * ScriptedAgent.OUT_TOKENS, r.outputTokens());
        assertEquals(0, Money.of("0.03", "USD").compareTo(r.estimatedCost()));
    }

    @Test
    void usage_isKept_whenRepairsAreExhausted() {
        ScriptedAgent inner = new ScriptedAgent("{}", "{}");

        AgentResponse r = new ContractEnforcingAgent(inner, schemaContract(1)).execute(AgentTask.of("x"));

        assertFalse(r.isSuccess());
        assertEquals(2 * ScriptedAgent.IN_TOKENS, r.inputTokens());
        assertEquals(2 * ScriptedAgent.OUT_TOKENS, r.outputTokens());
    }

    @Test
    void innerFailureDuringRepair_isReturned_notRepairedAgain() {
        ScriptedAgent inner = new ScriptedAgent("{}", ScriptedAgent.FAIL, "{\"total\":1}");

        AgentResponse r = new ContractEnforcingAgent(inner, schemaContract(3)).execute(AgentTask.of("x"));

        assertFalse(r.isSuccess());
        assertEquals("boom", r.failureReason());
        assertEquals(2, inner.inputs.size());
        assertEquals(ScriptedAgent.IN_TOKENS, r.inputTokens(), "the first attempt's usage is kept");
    }

    @Test
    void inputRejection_isNeverRepaired() {
        ScriptedAgent inner = new ScriptedAgent("{\"total\":1}");
        AgentContract contract = AgentContract.builder()
                .addInputProcessor(in -> ProcessingResult.reject("no"))
                .outputRepairAttempts(3)
                .build();

        AgentResponse r = new ContractEnforcingAgent(inner, contract).execute(AgentTask.of("x"));

        assertFalse(r.isSuccess());
        assertTrue(inner.inputs.isEmpty());
    }

    @Test
    void repairedAnswer_stillGoesThroughTransformingProcessors() {
        ScriptedAgent inner = new ScriptedAgent("```json\n{}\n```", "```json\n{\"total\":2}\n```");
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(SCHEMA);
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(MarkdownFenceStripper.instance())
                .addOutputProcessor(validator)
                .outputRepairAttempts(1)
                .build();

        AgentResponse r = new ContractEnforcingAgent(inner, contract).execute(AgentTask.of("x"));

        assertTrue(r.isSuccess(), r.failureReason());
        assertEquals("{\"total\":2}", r.content().strip());
    }

    @Test
    void longRejectedAnswer_isTruncatedInTheRepairInput() {
        String huge = "{\"total\":\"" + "x".repeat(10_000) + "\"}";
        ScriptedAgent inner = new ScriptedAgent(huge, "{\"total\":1}");

        new ContractEnforcingAgent(inner, schemaContract(1)).execute(AgentTask.of("x"));

        assertTrue(inner.inputs.get(1).length() < 6_000, "length " + inner.inputs.get(1).length());
        assertTrue(inner.inputs.get(1).contains("[… truncated]"));
    }

    @Test
    void negativeAttempts_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> AgentContract.builder().outputRepairAttempts(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentContract(List.of(), List.of(), List.of(), List.of(), null, null, -1));
    }

    // ── Stub ──────────────────────────────────────────────────────────────────

    /** Answers from a script, one entry per execute(); {@link #FAIL} answers with a failure. */
    private static final class ScriptedAgent implements AraAgent {
        static final String FAIL       = "<<fail>>";
        static final int    ITERATIONS = 2;
        static final int    IN_TOKENS  = 100;
        static final int    OUT_TOKENS = 20;

        final Deque<String>   script;
        final List<String>    inputs = new ArrayList<>();
        final List<AgentTask> tasks  = new ArrayList<>();

        ScriptedAgent(String... answers) { this.script = new ArrayDeque<>(List.of(answers)); }

        @Override public AgentId agentId() { return ID; }
        @Override public AgentConfig config() { return null; }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public void terminate() {}

        @Override
        public AgentResponse execute(AgentTask task) {
            inputs.add(task.input());
            tasks.add(task);
            String answer = script.removeFirst();
            if (FAIL.equals(answer)) {
                return AgentResponse.failure(task.taskId(), ID, "boom", Duration.ofMillis(1));
            }
            return AgentResponse.success(task.taskId(), ID, answer, ITERATIONS, IN_TOKENS, OUT_TOKENS,
                    Money.of("0.01", "USD"), Duration.ofMillis(1), List.of());
        }
    }
}
