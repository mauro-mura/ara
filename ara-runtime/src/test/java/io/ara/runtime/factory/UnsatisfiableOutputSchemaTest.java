package io.ara.runtime.factory;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.contract.JsonSchemaValidator;
import io.ara.runtime.contract.MarkdownFenceStripper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A structured-output contract reaches the model by exactly one route — the schema appended to
 * the system prompt — and {@code ContractEnforcer} takes that route only when
 * {@code nativeJsonSchema} is {@code false}. With it {@code true} the schema is supposed to
 * travel as a provider-native {@code response_format}, which no adapter sends.
 *
 * <p>That combination used to produce a model never told about the schema, answering in prose,
 * and a validator rejecting every answer — a task failing on every run with a message about a
 * missing field, pointing at the model rather than at the flag responsible. These tests pin the
 * rejection, and pin that the working default keeps working.
 */
class UnsatisfiableOutputSchemaTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{"party":{"type":"string"}},"required":["party"]}""";

    /** Records the system prompt it was sent, then answers with schema-shaped JSON. */
    private static final class PromptCapturingClient implements LlmClient {
        final List<String> systemPrompts = new ArrayList<>();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            messages.stream()
                    .filter(m -> "system".equals(m.role()))
                    .forEach(m -> systemPrompts.add(m.content()));
            return new LlmCompletion(
                    "Action: FINAL_ANSWER\nAnswer: {\"party\":\"ACME S.p.A.\"}", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "prompt-capturing"; }
    }

    private static AgentConfig configWith(AgentId id, boolean nativeJsonSchema) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.builder()
                        .transportId(id.value())
                        .nativeJsonSchema(nativeJsonSchema)
                        .build())
                .plannerStrategy("react")
                .maxIterations(2)
                .build();
    }

    private static AgentContract schemaContract() {
        return AgentContract.builder()
                .outputSchema(JsonSchemaValidator.forOutput(SCHEMA))
                .addOutputProcessor(MarkdownFenceStripper.instance())
                .addOutputProcessor(JsonSchemaValidator.forOutput(SCHEMA))
                .build();
    }

    @Test
    void an_output_schema_with_nativeJsonSchema_is_rejected_at_agent_creation() {
        AgentId id = AgentId.of("native-schema-agent");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), new PromptCapturingClient())
                .build();
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> runtime.createAgent(configWith(id, true), schemaContract()));

            // The message has to name the flag, not just the symptom: the whole point is that
            // the caller should not have to guess which of the two settings is the problem.
            assertTrue(ex.getMessage().contains("nativeJsonSchema"), ex.getMessage());
            assertTrue(ex.getMessage().contains(id.value()), ex.getMessage());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void a_rejected_configuration_registers_no_agent() {
        AgentId id = AgentId.of("not-registered-agent");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), new PromptCapturingClient())
                .build();
        try {
            assertThrows(IllegalStateException.class,
                    () -> runtime.createAgent(configWith(id, true), schemaContract()));

            assertEquals(0, runtime.registry().count(),
                    "a configuration rejected before anything is built must leave nothing behind");
        } finally {
            runtime.stop();
        }
    }

    @Test
    void the_default_appends_the_schema_to_the_system_prompt_and_validates_the_answer() {
        AgentId id = AgentId.of("prompt-schema-agent");
        PromptCapturingClient client = new PromptCapturingClient();
        AraRuntime runtime = AraRuntime.builder().llmClient(id.value(), client).build();
        try {
            AraAgent agent = runtime.createAgent(configWith(id, false), schemaContract());
            AgentResponse response = agent.execute(AgentTask.of("extract the counterparty"));

            assertTrue(response.isSuccess(), () -> "execute failed: " + response.failureReason());
            assertTrue(client.systemPrompts.stream().anyMatch(p -> p.contains("\"party\"")),
                    "the schema must reach the model through the system prompt: " + client.systemPrompts);
            assertTrue(response.content().contains("ACME"), response.content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void nativeJsonSchema_without_an_output_schema_is_left_alone() {
        // The flag on its own constrains nothing and breaks nothing — only the combination is
        // unsatisfiable, so rejecting the flag by itself would be a gratuitous restriction.
        AgentId id = AgentId.of("no-schema-agent");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), new PromptCapturingClient())
                .build();
        try {
            AgentContract inputOnly = AgentContract.builder()
                    .addInputProcessor(io.ara.core.agent.processor.ProcessingResult::pass)
                    .build();

            assertDoesNotThrow(() -> runtime.createAgent(configWith(id, true), inputOnly));
        } finally {
            runtime.stop();
        }
    }
}
