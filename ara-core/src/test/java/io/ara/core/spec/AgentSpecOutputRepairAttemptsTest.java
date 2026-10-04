package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code outputRepairAttempts}: how many times a rejected answer is repaired — an overlay that moves {@link AgentSpec#effectiveHash()} and nothing else. */
class AgentSpecOutputRepairAttemptsTest {

    private static AgentSpec base() {
        return AgentSpec.root(AgentConfig.defaults().agentType("analyst")
                .systemPrompt("Analyse.").primaryLlm(LlmProfile.of("test-model")).build());
    }

    @Test
    void aRootSpecDoesNotRepair() {
        assertEquals(0, base().outputRepairAttempts());
    }

    @Test
    void withOutputRepairAttempts_setsIt_andTouchesNothingElse() {
        AgentSpec base = base().withOutputSchemaRef("out-1").withFewShotRefs(List.of("fs-1"));

        AgentSpec with = base.withOutputRepairAttempts(2);

        assertEquals(2, with.outputRepairAttempts());
        assertEquals(base.config(), with.config());
        assertEquals(base.lineage(), with.lineage());
        assertEquals("out-1", with.outputSchemaRef());
        assertEquals(List.of("fs-1"), with.fewShotRefs());
    }

    @Test
    void aNegativeCount_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> base().withOutputRepairAttempts(-1));
    }

    @Test
    void effectiveHash_isTheSpecHash_whileRepairIsOff_andMovesWhenItIsOn() {
        AgentSpec base = base();

        assertEquals(base.specHash(), base.withOutputRepairAttempts(0).effectiveHash());
        assertNotEquals(base.effectiveHash(), base.withOutputRepairAttempts(1).effectiveHash());
        assertNotEquals(base.withOutputRepairAttempts(1).effectiveHash(), base.withOutputRepairAttempts(2).effectiveHash());
        assertEquals(base.specHash(), base.withOutputRepairAttempts(1).specHash(), "the behavioural hash does not move");
    }

    @Test
    void repairOnAnOutputSchema_isADifferentCandidateFromTheSchemaAlone() {
        AgentSpec schemaOnly = base().withOutputSchemaRef("out-1");

        assertNotEquals(schemaOnly.effectiveHash(), schemaOnly.withOutputRepairAttempts(1).effectiveHash());
        assertEquals(schemaOnly.effectiveHash(), schemaOnly.withOutputRepairAttempts(0).effectiveHash());
    }

    @Test
    void everyCopyCarriesTheCount() {
        AgentSpec spec = base().withOutputRepairAttempts(3);

        assertEquals(3, spec.withStatus(new SpecStatus.Shadow()).outputRepairAttempts());
        assertEquals(3, spec.withFewShotRefs(List.of("fs")).outputRepairAttempts());
        assertEquals(3, spec.withSchemaRef("in").outputRepairAttempts());
        assertEquals(3, spec.withOutputSchemaRef("out").outputRepairAttempts());
        assertEquals(3, spec.derive(AgentConfig.defaults().agentType("analyst").systemPrompt("Other.")
                .primaryLlm(LlmProfile.of("test-model")).build()).outputRepairAttempts());
    }

    @Test
    void theFiveArgConstructor_keepsWorking_withRepairOff() {
        AgentSpec base = base();

        AgentSpec viaFive = new AgentSpec(base.config(), base.lineage(), List.of(), null, "out-1");

        assertEquals(0, viaFive.outputRepairAttempts());
        assertEquals("out-1", viaFive.outputSchemaRef());
    }
}
