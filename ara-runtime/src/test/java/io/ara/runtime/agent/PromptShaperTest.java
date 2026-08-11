package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.processor.SchemaProvider;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.contract.JsonSchemaValidator;
import io.ara.runtime.contract.MinLengthValidator;
import io.ara.runtime.contract.PromptTemplate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ADR-014: PromptShaper chain, PromptTemplate, outputSchema, OutputFormatEnforcer.
 */
class PromptShaperTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * A test agent that captures the effective system prompt it received
     * by implementing PromptOverridable (it IS an AgentInstance in production;
     * here we use a lightweight stub that records the prompt).
     */
    private static CapturingAgent capturingAgent(String baseSystemPrompt) {
        return new CapturingAgent(baseSystemPrompt);
    }

    // ── PromptTemplate tests ──────────────────────────────────────────────────

    @Test
    void promptTemplate_resolvesPlaceholdersFromTaskContext() {
        CapturingAgent agent = capturingAgent("Hello {name}, project is {project}.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.instance())
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test", Map.of("name", "Mario", "project", "ARA")));

        assertEquals("Hello Mario, project is ARA.", agent.capturedPrompt());
    }

    @Test
    void promptTemplate_usesDefaultWhenContextKeyAbsent() {
        CapturingAgent agent = capturingAgent("Rispondi in {lang}.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "italiano")))
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test"));  // no lang in context

        assertEquals("Rispondi in italiano.", agent.capturedPrompt());
    }

    @Test
    void promptTemplate_contextOverridesDefault() {
        CapturingAgent agent = capturingAgent("Rispondi in {lang}.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "italiano")))
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test", Map.of("lang", "english")));

        assertEquals("Rispondi in english.", agent.capturedPrompt());
    }

    @Test
    void promptTemplate_leavesUnresolvedPlaceholderUnchanged() {
        CapturingAgent agent = capturingAgent("Hello {unknown}.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.instance())
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test"));

        assertEquals("Hello {unknown}.", agent.capturedPrompt());
    }

    @Test
    void promptTemplate_toleratesWhitespaceAroundKey() {
        CapturingAgent agent = capturingAgent("Hello { name }, project is {  project  }.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.instance())
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test", Map.of("name", "Mario", "project", "ARA")));

        assertEquals("Hello Mario, project is ARA.", agent.capturedPrompt());
    }

    @Test
    void promptTemplate_toleratesWhitespaceWithCustomDelimiters() {
        CapturingAgent agent = capturingAgent("Rispondi in {{ lang }}.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "italiano"))
                        .delimiters("{{", "}}"))
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test"));

        assertEquals("Rispondi in italiano.", agent.capturedPrompt());
    }

    @Test
    void promptTemplate_strictModeThrowsOnUnresolvedPlaceholder() {
        CapturingAgent agent = capturingAgent("Hello {missing}.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.instance().strict())
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);

        assertThrows(IllegalStateException.class,
                () -> enforced.execute(AgentTask.of("test")));
    }

    // ── Lambda shaper test ────────────────────────────────────────────────────

    @Test
    void lambdaShaper_appendsStaticTextToPrompt() {
        CapturingAgent agent = capturingAgent("Base prompt.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper((prompt, task) -> prompt + " POLICY: no PII.")
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test"));

        assertEquals("Base prompt. POLICY: no PII.", agent.capturedPrompt());
    }

    // ── Shaper chain ordering ─────────────────────────────────────────────────

    @Test
    void shaperChain_appliedInDeclaredOrder() {
        CapturingAgent agent = capturingAgent("Base.");

        AgentContract contract = AgentContract.builder()
                .addPromptShaper((p, t) -> p + " [1]")
                .addPromptShaper((p, t) -> p + " [2]")
                .addPromptShaper((p, t) -> p + " [3]")
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test"));

        assertEquals("Base. [1] [2] [3]", agent.capturedPrompt());
    }

    // ── outputSchema / OutputFormatEnforcer ───────────────────────────────────

    @Test
    void outputSchema_appendsFormatInstructionsAfterShapers() {
        CapturingAgent agent = capturingAgent("Classify the input.");

        String schema = """
                {"type":"object","required":["category"],"properties":{"category":{"type":"string"}}}
                """;
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(schema);

        AgentContract contract = AgentContract.builder()
                .addPromptShaper((p, t) -> p + " Be concise.")
                .outputSchema(validator)
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test"));

        String captured = agent.capturedPrompt();
        assertTrue(captured.startsWith("Classify the input. Be concise."),
                "shapers applied before outputSchema");
        assertTrue(captured.contains("IMPORTANT: Respond ONLY with a single valid JSON object"),
                "format instructions appended");
        assertTrue(captured.contains("\"category\""),
                "schema included in format instructions");
    }

    @Test
    void outputSchema_noConflictWithPromptTemplatePlaceholders() {
        // PromptTemplate resolves {lang} BEFORE outputSchema appends JSON with {}
        CapturingAgent agent = capturingAgent("Respond in {lang}.");

        String schema = """
                {"type":"object","required":["result"],"properties":{"result":{"type":"string"}}}
                """;
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(schema);

        AgentContract contract = AgentContract.builder()
                .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "italiano")))
                .outputSchema(validator)
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        enforced.execute(AgentTask.of("test"));

        String captured = agent.capturedPrompt();
        assertTrue(captured.startsWith("Respond in italiano."),
                "placeholder resolved before outputSchema appended JSON");
        assertTrue(captured.contains("\"result\""),
                "schema appended correctly");
        assertFalse(captured.contains("{lang}"),
                "no leftover placeholder");
    }

    // ── SchemaProvider / JsonSchemaValidator ──────────────────────────────────

    @Test
    void jsonSchemaValidator_forOutput_implementsSchemaProvider() {
        String schema = """
                {"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}
                """;
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(schema);

        assertInstanceOf(SchemaProvider.class, validator);
        assertEquals(schema.strip(), validator.jsonSchema().strip());
    }

    @Test
    void jsonSchemaValidator_forOutput_parsesRequiredFieldsFromSchema() {
        String schema = """
                {"type":"object","required":["nome","cognome"],"properties":{"nome":{"type":"string"},"cognome":{"type":"string"}}}
                """;
        JsonSchemaValidator validator = JsonSchemaValidator.forOutput(schema);

        // valid payload — should pass
        var pass = validator.process("{\"nome\":\"Mario\",\"cognome\":\"Rossi\"}");
        assertTrue(pass instanceof io.ara.core.agent.processor.ProcessingResult.Pass);

        // missing field — should reject
        var reject = validator.process("{\"nome\":\"Mario\"}");
        assertTrue(reject instanceof io.ara.core.agent.processor.ProcessingResult.Reject);
    }

    @Test
    void jsonSchemaValidator_requiring_throwsOnJsonSchema() {
        JsonSchemaValidator validator = JsonSchemaValidator.requiring("field");
        assertThrows(IllegalStateException.class, validator::jsonSchema);
    }

    // ── No-shaping path is unchanged ─────────────────────────────────────────

    @Test
    void noShapers_contractBehaviourUnchanged() {
        CapturingAgent agent = capturingAgent("Original prompt.");

        AgentContract contract = AgentContract.builder()
                .addOutputProcessor(MinLengthValidator.atLeast(1))
                .build();

        AraAgent enforced = new ContractEnforcingAgent(agent, contract);
        AgentResponse resp = enforced.execute(AgentTask.of("hello"));

        assertTrue(resp.isSuccess());
        assertEquals("Original prompt.", agent.capturedPrompt(),
                "system prompt must not be modified when no shapers are present");
    }

    // ── CapturingAgent stub ───────────────────────────────────────────────────

    /**
     * Minimal AraAgent + PromptOverridable stub that records the effective system prompt.
     * Not a real AgentInstance — but implements the same PromptOverridable interface
     * so ContractEnforcingAgent can deliver the shaped prompt.
     */
    static final class CapturingAgent
            implements AraAgent {

        private final String basePrompt;
        private final AtomicReference<String> captured = new AtomicReference<>();

        CapturingAgent(String basePrompt) {
            this.basePrompt = basePrompt;
            captured.set(basePrompt);  // default: unshaped
        }

        String capturedPrompt() { return captured.get(); }

        @Override
        public AgentResponse execute(AgentTask task) {
            String prompt = task.runContext().promptVar(AgentInstance.CTX_SYSTEM_PROMPT, basePrompt);
            captured.set(prompt);
            return AgentResponse.success(task.taskId(), agentId(), "ok",
                    1, 10, 0.0, Duration.ofMillis(1), List.of());
        }

        @Override public AgentId agentId() { return AgentId.of("capturing-agent"); }
        @Override public AgentConfig config() {
            return AgentConfig.defaults().systemPrompt(basePrompt)
                    .primaryLlm(LlmProfile.builder().nativeJsonSchema(false).build())
                    .build();
        }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public void terminate() {}
    }
}
