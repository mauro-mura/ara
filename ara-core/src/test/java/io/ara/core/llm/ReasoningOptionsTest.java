package io.ara.core.llm;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import io.ara.core.spec.AgentSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reasoning options on a profile: unset by default, absent from the identity of an agent that
 * never mentions them, part of it when set, carried per call, and never lost by copying a profile.
 */
class ReasoningOptionsTest {

    private static AgentConfig.Builder agent() {
        return AgentConfig.defaults().agentType("analyst").systemPrompt("p");
    }

    // ── defaults and validation ────────────────────────────────────────────────────────

    @Test
    void aProfile_saysNothingAboutReasoning_unlessAskedTo() {
        LlmProfile profile = LlmProfile.of("main");

        assertNull(profile.reasoningEffort());
        assertNull(profile.thinkingBudgetTokens());
        assertNull(profile.returnReasoning());
    }

    @Test
    void theOptions_areSetThroughTheBuilder() {
        LlmProfile profile = LlmProfile.builder().transportId("main")
                .reasoningEffort(ReasoningEffort.HIGH).thinkingBudgetTokens(2048).returnReasoning(true).build();

        assertEquals(ReasoningEffort.HIGH, profile.reasoningEffort());
        assertEquals(2048, profile.thinkingBudgetTokens());
        assertEquals(true, profile.returnReasoning());
    }

    @Test
    void aBudgetBelowOne_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmProfile.builder().transportId("m").thinkingBudgetTokens(0).build());
    }

    @Test
    void theEffort_hasTheWordsProvidersExpect() {
        assertEquals("low", ReasoningEffort.LOW.wireValue());
        assertEquals("medium", ReasoningEffort.MEDIUM.wireValue());
        assertEquals("high", ReasoningEffort.HIGH.wireValue());
    }

    // ── identity ───────────────────────────────────────────────────────────────────────

    @Test
    void anAgentThatNeverMentionsReasoning_keepsItsHash() {
        AgentConfig plain = agent().primaryLlm(LlmProfile.of("main")).build();
        AgentConfig explicitlyUnset = agent().primaryLlm(LlmProfile.builder().transportId("main")
                .reasoningEffort(null).thinkingBudgetTokens(null).returnReasoning(null).build()).build();

        // A null component is skipped when the hash is computed, so adding the options changed no
        // existing agent's identity. A boolean or an int would have been written as false/0 and moved
        // every hash in every archive.
        assertEquals(AgentSpec.root(plain).specHash(), AgentSpec.root(explicitlyUnset).specHash());
    }

    @Test
    void settingAnOption_changesTheHash_becauseItChangesBehaviourAndCost() {
        AgentConfig plain = agent().primaryLlm(LlmProfile.of("main")).build();
        String base = AgentSpec.root(plain).specHash();

        for (LlmProfile changed : List.of(
                LlmProfile.builder().transportId("main").reasoningEffort(ReasoningEffort.LOW).build(),
                LlmProfile.builder().transportId("main").thinkingBudgetTokens(1000).build(),
                LlmProfile.builder().transportId("main").returnReasoning(true).build())) {
            assertNotEquals(base, AgentSpec.root(agent().primaryLlm(changed).build()).specHash());
        }
    }

    // ── copying a profile ──────────────────────────────────────────────────────────────

    @Test
    void toBuilder_keepsEveryField_includingTheReasoningOptions() {
        LlmProfile original = LlmProfile.builder().transportId("main").temperature(0.3).topP(0.8).maxTokens(500)
                .costCurrency("USD").costBudget(Budget.limited(Money.of("2", "USD")))
                .costInputPer1kTokens(Money.of("0.001", "USD")).costOutputPer1kTokens(Money.of("0.002", "USD"))
                .streamingEnabled(true).nativeJsonSchema(true).pinnedTransportVersion(4L)
                .reasoningEffort(ReasoningEffort.MEDIUM).thinkingBudgetTokens(900).returnReasoning(true).build();

        assertEquals(original, original.toBuilder().build());
    }

    @Test
    void toBuilder_keepsAnInlineTransport() {
        LlmProfile original = LlmProfile.builder().baseUrl("http://x").modelName("gpt").apiKey("k").build();

        assertEquals(original, original.toBuilder().build());
    }

    @Test
    void changingOneField_throughToBuilder_leavesTheReasoningOptionsAlone() {
        LlmProfile original = LlmProfile.builder().transportId("main").temperature(0.2)
                .reasoningEffort(ReasoningEffort.HIGH).returnReasoning(true).build();

        LlmProfile mutated = original.toBuilder().temperature(0.9).build();

        assertEquals(0.9, mutated.temperature());
        assertEquals(ReasoningEffort.HIGH, mutated.reasoningEffort());
        assertEquals(true, mutated.returnReasoning());
    }

    @Test
    void theOldTwelveArgumentConstructor_stillExists_andLeavesTheOptionsUnset() {
        LlmProfile profile = new LlmProfile("main", null, null, null, null, Budget.unlimited(), "EUR", false, false,
                Money.zero("EUR"), Money.zero("EUR"), null);

        assertNull(profile.reasoningEffort());
        assertNull(profile.returnReasoning());
    }

    // ── per call ───────────────────────────────────────────────────────────────────────

    @Test
    void theCallContext_carriesTheAgentsOptions() {
        AgentConfig config = agent().primaryLlm(LlmProfile.builder().transportId("main")
                .reasoningEffort(ReasoningEffort.LOW).thinkingBudgetTokens(512).returnReasoning(true).build()).build();

        LlmCallContext context = LlmCallContext.from(config);

        assertEquals(ReasoningEffort.LOW, context.reasoningEffort());
        assertEquals(512, context.thinkingBudgetTokens());
        assertEquals(true, context.returnReasoning());
        assertTrue(context.hasReasoningOptions());
    }

    @Test
    void theCallContext_ofAnAgentWithNoOptions_hasNone_andKeepsThemThroughCopies() {
        LlmCallContext plain = LlmCallContext.from(agent().primaryLlm(LlmProfile.of("main")).build());
        assertFalse(plain.hasReasoningOptions());

        LlmCallContext withOptions = LlmCallContext.from(agent().primaryLlm(LlmProfile.builder().transportId("main")
                .reasoningEffort(ReasoningEffort.HIGH).build()).build());
        // Every with*() goes through a copy; the options must survive it.
        assertEquals(ReasoningEffort.HIGH, withOptions.withTemperature(0.5).reasoningEffort());
        assertEquals(ReasoningEffort.HIGH, withOptions.withCompletionSink(c -> { }).reasoningEffort());
    }

    @Test
    void returnReasoningFalse_isNotAReasoningOption() {
        LlmCallContext context = LlmCallContext.from(agent().primaryLlm(
                LlmProfile.builder().transportId("main").returnReasoning(false).build()).build());

        assertFalse(context.hasReasoningOptions());
    }

    // ── completion and step ────────────────────────────────────────────────────────────

    @Test
    void aCompletion_hasNoReasoning_unlessTheProviderGaveSome() {
        assertFalse(new LlmCompletion("hi", 1, 1, "stop", null).hasReasoning());
        assertFalse(new LlmCompletion("hi", 1, 1, "stop", null, null, List.of(), false, "  ").hasReasoning());
        assertTrue(new LlmCompletion("hi", 1, 1, "stop", null, null, List.of(), false, "because").hasReasoning());
    }

    @Test
    void aReasoningStep_isItsOwnKind() {
        ExecutionStep step = ExecutionStep.reasoning("weighing the options", 2);

        assertEquals(StepType.REASONING, step.type());
        assertEquals("reasoning", step.type().wireValue());
        assertEquals("weighing the options", step.content());
        assertNull(step.toolId());
        assertNull(step.success());
        assertEquals(StepType.REASONING, StepType.fromWireValue("reasoning"));
    }
}
