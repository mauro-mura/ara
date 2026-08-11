package io.ara.runtime.contract;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.agent.ContractEnforcingAgent;
import io.ara.runtime.agent.AgentInstance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContractEnforcingAgentTest {

    private static final AgentId ID = AgentId.of("test-agent");

    private static AraAgent echoAgent(String fixedOutput) {
        return new StubAgent(ID, fixedOutput, true);
    }

    private static AraAgent failingAgent() {
        return new StubAgent(ID, "inner failure", false);
    }

    @Test
    void passthrough_when_contract_empty() {
        AraAgent agent = new ContractEnforcingAgent(echoAgent("hello"), AgentContract.empty());
        AgentResponse r = agent.execute(AgentTask.of("hi"));
        assertTrue(r.isSuccess());
        assertEquals("hello", r.content());
    }

    @Test
    void input_processor_transforms_input() {
        AgentContract contract = AgentContract.builder()
                .addInputProcessor(input -> ProcessingResult.pass(input.toUpperCase()))
                .build();
        // inner agent echoes its input — we spy via a capturing stub
        CapturingAgent inner = new CapturingAgent(ID);
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("hello"));
        assertEquals("HELLO", inner.lastInput);
    }

    @Test
    void input_processor_reject_returns_failure() {
        AgentContract contract = AgentContract.builder()
                .addInputProcessor(input -> ProcessingResult.reject("input too short"))
                .build();
        AraAgent agent = new ContractEnforcingAgent(echoAgent("x"), contract);
        AgentResponse r = agent.execute(AgentTask.of("hi"));
        assertFalse(r.isSuccess());
        assertTrue(r.failureReason().contains("input too short"));
    }

    @Test
    void output_processor_transforms_output() {
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(out -> ProcessingResult.pass(out.strip()))
                .build();
        AraAgent agent = new ContractEnforcingAgent(echoAgent("  spaces  "), contract);
        AgentResponse r = agent.execute(AgentTask.of("test"));
        assertEquals("spaces", r.content());
    }

    @Test
    void output_processor_reject_when_inner_fails() {
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(out -> ProcessingResult.reject("bad output"))
                .build();
        // inner fails — output processor should NOT be reached
        AraAgent agent = new ContractEnforcingAgent(failingAgent(), contract);
        AgentResponse r = agent.execute(AgentTask.of("test"));
        assertFalse(r.isSuccess());
        // failure comes from inner, not from output processor
        assertTrue(r.failureReason().contains("inner failure"));
    }

    @Test
    void output_processor_reject_on_success() {
        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(out -> ProcessingResult.reject("output rejected"))
                .build();
        AraAgent agent = new ContractEnforcingAgent(echoAgent("ok"), contract);
        AgentResponse r = agent.execute(AgentTask.of("test"));
        assertFalse(r.isSuccess());
        assertTrue(r.failureReason().contains("output rejected"));
    }

    @Test
    void multiple_processors_applied_in_order() {
        AgentContract contract = AgentContract.builder()
                .addInputProcessor(in -> ProcessingResult.pass(in + "_A"))
                .addInputProcessor(in -> ProcessingResult.pass(in + "_B"))
                .build();
        CapturingAgent inner = new CapturingAgent(ID);
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("x"));
        assertEquals("x_A_B", inner.lastInput);
    }

    // ── PromptShaper / PromptOverridable tests ────────────────────────────────

    @Test
    void shaper_is_applied_when_inner_is_prompt_overridable() {
        AgentContract contract = AgentContract.builder()
                .addPromptShaper((prompt, task) -> prompt + " [shaped]")
                .build();
        CapturingPromptOverridableAgent inner = new CapturingPromptOverridableAgent(ID);
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("hello"));
        assertNotNull(inner.lastSystemPrompt);
        assertTrue(inner.lastSystemPrompt.endsWith(" [shaped]"),
                "expected shaped prompt, got: " + inner.lastSystemPrompt);
    }

    @Test
    void shaper_chain_applied_in_order() {
        AgentContract contract = AgentContract.builder()
                .addPromptShaper((prompt, task) -> prompt + "_A")
                .addPromptShaper((prompt, task) -> prompt + "_B")
                .build();
        CapturingPromptOverridableAgent inner = new CapturingPromptOverridableAgent(ID);
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("x"));
        assertTrue(inner.lastSystemPrompt.endsWith("_A_B"),
                "expected _A_B suffix, got: " + inner.lastSystemPrompt);
    }

    @Test
    void shaper_uses_config_system_prompt_as_base() {
        AgentContract contract = AgentContract.builder()
                .addPromptShaper((prompt, task) -> prompt + " extra")
                .build();
        CapturingPromptOverridableAgent inner =
                new CapturingPromptOverridableAgent(ID, "base-prompt");
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("x"));
        assertEquals("base-prompt extra", inner.lastSystemPrompt);
    }

    @Test
    void shaper_null_config_uses_empty_string_as_base() {
        AgentContract contract = AgentContract.builder()
                .addPromptShaper((prompt, task) -> prompt + "appended")
                .build();
        // config() returns null → base prompt must be ""
        CapturingPromptOverridableAgent inner =
                new CapturingPromptOverridableAgent(ID, null);  // null config
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("x"));
        assertEquals("appended", inner.lastSystemPrompt);
    }

    @Test
    void output_schema_appended_to_system_prompt() {
        AgentContract contract = AgentContract.builder()
                .outputSchema(() -> "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}")
                .build();
        CapturingPromptOverridableAgent inner =
                new CapturingPromptOverridableAgent(ID, "base");
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("x"));
        assertNotNull(inner.lastSystemPrompt);
        assertTrue(inner.lastSystemPrompt.contains("JSON"),
                "output schema should inject JSON instructions, got: " + inner.lastSystemPrompt);
    }

    @Test
    void shaper_receives_transformed_task_input() {
        // Input processor uppercases input; shaper should see the uppercased version
        AgentContract contract = AgentContract.builder()
                .addInputProcessor(in -> ProcessingResult.pass(in.toUpperCase()))
                .addPromptShaper((prompt, task) -> prompt + " task=" + task.input())
                .build();
        CapturingPromptOverridableAgent inner = new CapturingPromptOverridableAgent(ID);
        AraAgent agent = new ContractEnforcingAgent(inner, contract);
        agent.execute(AgentTask.of("hello"));
        assertTrue(inner.lastSystemPrompt.contains("task=HELLO"),
                "shaper should receive uppercased input, got: " + inner.lastSystemPrompt);
    }

    // ── Attachment propagation through the contract chain (ADR-037) ──────────

    private record SecurityContext(String clearance) {}

    @Test
    void attachment_survives_full_contract_chain_with_shaping_and_output_schema() {
        // Exercises the ContractEnforcer path that chains withInput, withHints
        // (via outputSchema), and withContextEntry before delegating to inner —
        // the exact sequence ADR-037 requires attachments to survive.
        AgentContract contract = AgentContract.builder()
                .addInputProcessor(in -> ProcessingResult.pass(in.toUpperCase()))
                .addPromptShaper((prompt, task) -> prompt + " task=" + task.input())
                .outputSchema(() -> "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}")
                .build();

        CapturingPromptOverridableAgent inner = new CapturingPromptOverridableAgent(ID, "base");
        AraAgent agent = new ContractEnforcingAgent(inner, contract);

        SecurityContext scx = new SecurityContext("top-secret");
        AgentTask task = AgentTask.of("hello").withAttachment("securityContext", scx);
        agent.execute(task);

        assertSame(scx, inner.lastTask.runContext().opaque("securityContext", SecurityContext.class),
                "attachment must reach inner.execute() after the full contract chain");
    }

    @Test
    void attachment_survives_passthrough_when_contract_empty() {
        CapturingAgent inner = new CapturingAgent(ID);
        AraAgent agent = new ContractEnforcingAgent(inner, AgentContract.empty());

        SecurityContext scx = new SecurityContext("clearance-1");
        agent.execute(AgentTask.of("hi").withAttachment("securityContext", scx));

        assertSame(scx, inner.lastTask.runContext().opaque("securityContext", SecurityContext.class));
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    private record StubAgent(AgentId agentId, String output, boolean success)
            implements AraAgent {
        @Override
        public AgentConfig config() { return null; }
        @Override
        public AgentState currentState() { return AgentState.IDLE; }
        @Override
        public AgentResponse execute(AgentTask task) {
            if (success) {
                return AgentResponse.success(task.taskId(), agentId, output,
                        1, 10, 0.0, Duration.ofMillis(1), List.of());
            } else {
                return AgentResponse.failure(task.taskId(), agentId, output, Duration.ofMillis(1));
            }
        }
        @Override public void terminate() {}
    }

    private static class CapturingAgent implements AraAgent {
        final AgentId agentId;
        String lastInput;
        AgentTask lastTask;

        CapturingAgent(AgentId id) { this.agentId = id; }

        @Override public AgentId agentId() { return agentId; }
        @Override public AgentConfig config() { return null; }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override
        public AgentResponse execute(AgentTask task) {
            lastInput = task.input();
            lastTask = task;
            return AgentResponse.success(task.taskId(), agentId, task.input(),
                    1, 10, 0.0, Duration.ofMillis(1), List.of());
        }
        @Override public void terminate() {}
    }

    private static class CapturingPromptOverridableAgent implements AraAgent {
        final AgentId agentId;
        final String  configuredPrompt;  // null means config() returns null
        String lastSystemPrompt;
        AgentTask lastTask;

        CapturingPromptOverridableAgent(AgentId id) { this(id, ""); }
        CapturingPromptOverridableAgent(AgentId id, String configuredPrompt) {
            this.agentId          = id;
            this.configuredPrompt = configuredPrompt;
        }

        @Override public AgentId agentId() { return agentId; }

        @Override
        public AgentConfig config() {
            if (configuredPrompt == null) return null;
            return AgentConfig.defaults().systemPrompt(configuredPrompt)
                    .primaryLlm(LlmProfile.builder().nativeJsonSchema(false).build())
                    .build();
        }

        @Override public AgentState currentState() { return AgentState.IDLE; }

        @Override
        public AgentResponse execute(AgentTask task) {
            lastSystemPrompt = task.runContext().promptVar(
                    AgentInstance.CTX_SYSTEM_PROMPT, configuredPrompt != null ? configuredPrompt : "");
            lastTask = task;
            return AgentResponse.success(task.taskId(), agentId, task.input(),
                    1, 10, 0.0, Duration.ofMillis(1), List.of());
        }

        @Override public void terminate() {}
    }
}
