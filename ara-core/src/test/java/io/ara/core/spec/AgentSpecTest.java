package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0065 acceptance criteria for {@link AgentSpec} / {@link SpecLineage}: same
 * behaviour hashes the same regardless of instance identity, a behavioural change
 * produces a new version with lineage, cosmetic fields and status changes do not move
 * the hash, and a spec built without a real {@code agentType} is rejected.
 */
class AgentSpecTest {

    /** A non-default, fully-specified config for the given capability. */
    private static AgentConfig.Builder builder(String agentType) {
        return AgentConfig.defaults()
                .agentType(agentType)
                .systemPrompt("You analyse quarterly filings and flag anomalies.")
                .primaryLlm(LlmProfile.of("test-model"))
                .plannerStrategy("react")
                .maxIterations(7)
                .tags(List.of("finance", "read-only"))
                .workingMemoryTokenBudget(2000);
    }

    private static AgentConfig cfg(String agentType) {
        return builder(agentType).build();
    }

    // 1 — same behaviour, independent construction (⇒ different random agentId) ⇒ same hash
    @Test
    void identicalBehaviour_builtIndependently_hashesTheSame() {
        AgentSpec a = AgentSpec.root(cfg("analyst"));
        AgentSpec b = AgentSpec.root(cfg("analyst"));

        assertNotEquals(a.config().agentId(), b.config().agentId(), "precondition: agentIds differ");
        assertEquals(a.specHash(), b.specHash());
        assertEquals(64, a.specHash().length());
    }

    // 2 — a behavioural change ⇒ new hash, version incremented, lineage recorded
    @Test
    void derive_withBehaviouralChange_bumpsVersionAndLinksParent() {
        AgentSpec parent = AgentSpec.root(cfg("analyst"));
        AgentConfig changed = builder("analyst")
                .systemPrompt("You analyse filings AND draft a summary memo.")
                .build();

        AgentSpec child = parent.derive(changed);

        assertNotEquals(parent.specHash(), child.specHash());
        assertEquals(1, parent.lineage().specVersion());
        assertEquals(2, child.lineage().specVersion());
        assertEquals(parent.specHash(), child.lineage().derivedFrom());
        assertInstanceOf(SpecStatus.Draft.class, child.lineage().status());
    }

    // 3 — cosmetic fields (agentId/name/description/version) are not behaviour
    @Test
    void cosmeticFields_doNotChangeTheHash() {
        AgentConfig one = builder("analyst").name("Alice").description("first").version("9.9.0").build();
        AgentConfig two = builder("analyst").name("Bob").description("second").version("0.0.1").build();

        assertEquals(AgentSpec.root(one).specHash(), AgentSpec.root(two).specHash());
    }

    // 3b — an LLM parameter, by contrast, IS behaviour
    @Test
    void llmParameterChange_changesTheHash() {
        AgentConfig cool = builder("analyst").primaryLlm(LlmProfile.builder().transportId("m").temperature(0.2).build()).build();
        AgentConfig warm = builder("analyst").primaryLlm(LlmProfile.builder().transportId("m").temperature(0.9).build()).build();

        assertNotEquals(AgentSpec.root(cool).specHash(), AgentSpec.root(warm).specHash());
    }

    // 4 — D4: agentType must be a real discriminator
    @Test
    void root_rejectsBlankOrGenericAgentType() {
        assertThrows(IllegalArgumentException.class, () -> AgentSpec.root(cfg("generic")));
        assertThrows(IllegalArgumentException.class, () -> AgentSpec.root(cfg("   ")));
    }

    @Test
    void derive_rejectsBlankOrGenericAgentType() {
        AgentSpec parent = AgentSpec.root(cfg("analyst"));
        assertThrows(IllegalArgumentException.class, () -> parent.derive(cfg("generic")));
    }

    // 5 — D5: a status transition keeps hash and version
    @Test
    void withStatus_keepsHashAndVersion() {
        AgentSpec draft = AgentSpec.root(cfg("analyst"));
        AgentSpec canary = draft.withStatus(new SpecStatus.Canary());

        assertEquals(draft.specHash(), canary.specHash());
        assertEquals(draft.lineage().specVersion(), canary.lineage().specVersion());
        assertInstanceOf(SpecStatus.Canary.class, canary.lineage().status());
        assertSame(draft.config(), canary.config());
    }

    // 6 — the shape of a root lineage
    @Test
    void root_lineageShape() {
        AgentSpec spec = AgentSpec.root(cfg("analyst"));
        SpecLineage lineage = spec.lineage();

        assertEquals(1, lineage.specVersion());
        assertNull(lineage.derivedFrom());
        assertInstanceOf(SpecStatus.Draft.class, lineage.status());
        assertTrue(spec.fewShotRefs().isEmpty(), "a root spec references no few-shot examples");
    }

    // 7 — hashing is deterministic across repeated calls on the same config
    @Test
    void hash_isStableAcrossCalls() {
        AgentConfig config = cfg("analyst");
        assertEquals(SpecLineage.hash(config), SpecLineage.hash(config));
    }

    // constructor guards
    @Test
    void constructorsRejectNulls() {
        assertThrows(NullPointerException.class, () -> AgentSpec.root(null));
        assertThrows(NullPointerException.class,
                () -> new AgentSpec(cfg("analyst"), null, List.of(), null));
    }

    // ADR-0066 D6 — the few-shot axis is orthogonal to behaviour and to lifecycle
    @Test
    void withFewShotRefs_isOrthogonalToHashVersionAndStatus() {
        AgentSpec base = AgentSpec.root(cfg("analyst")).withStatus(new SpecStatus.Canary());
        AgentSpec withRefs = base.withFewShotRefs(List.of("acme:few/anomaly-1", "acme:few/anomaly-2"));

        assertEquals(base.specHash(), withRefs.specHash());
        assertEquals(base.lineage().specVersion(), withRefs.lineage().specVersion());
        assertInstanceOf(SpecStatus.Canary.class, withRefs.lineage().status());
        assertEquals(List.of("acme:few/anomaly-1", "acme:few/anomaly-2"), withRefs.fewShotRefs());
    }

    @Test
    void derive_carriesFewShotRefsOver() {
        AgentSpec parent = AgentSpec.root(cfg("analyst")).withFewShotRefs(List.of("acme:few/x"));
        AgentSpec child = parent.derive(builder("analyst").systemPrompt("changed").build());

        assertEquals(List.of("acme:few/x"), child.fewShotRefs());
    }

    @Test
    void fewShotRefs_areDefensivelyCopiedAndImmutable() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        AgentSpec spec = AgentSpec.root(cfg("analyst")).withFewShotRefs(mutable);
        mutable.add("b");

        assertEquals(List.of("a"), spec.fewShotRefs());
        assertThrows(UnsupportedOperationException.class, () -> spec.fewShotRefs().add("c"));
    }

    // ── the schema-ref overlay axis (ADR-0077 D1, axis 5) ─────────────────────────────

    @Test
    void withSchemaRef_isOrthogonalToHashVersionAndStatus() {
        AgentSpec base = AgentSpec.root(cfg("analyst")).withStatus(new SpecStatus.Canary());
        AgentSpec withSchema = base.withSchemaRef("schema-42");

        assertEquals(base.specHash(), withSchema.specHash(), "an overlay ref is not behavioural identity");
        assertEquals(base.lineage().specVersion(), withSchema.lineage().specVersion());
        assertInstanceOf(SpecStatus.Canary.class, withSchema.lineage().status());
        assertEquals("schema-42", withSchema.schemaRef());
    }

    @Test
    void derive_carriesTheSchemaRefOver() {
        AgentSpec parent = AgentSpec.root(cfg("analyst")).withSchemaRef("schema-42");
        AgentSpec child = parent.derive(builder("analyst").systemPrompt("changed").build());

        assertEquals("schema-42", child.schemaRef());
        assertNotEquals(parent.specHash(), child.specHash(), "the config axis still moves the hash");
    }

    @Test
    void aRootSpecDeclaresNoSchema() {
        assertNull(AgentSpec.root(cfg("analyst")).schemaRef());
    }

    // ── effectiveHash: the evolution cycle's candidate id ─────────────────────────────

    @Test
    void effectiveHash_equalsSpecHashWhenNoOverlayIsSet() {
        AgentSpec spec = AgentSpec.root(cfg("analyst"));

        assertEquals(spec.specHash(), spec.effectiveHash(),
                "the five config-shaped axes must see no new identifier at all");
    }

    @Test
    void effectiveHash_distinguishesAnOverlayCandidateFromItsOwnBaseline() {
        AgentSpec base = AgentSpec.root(cfg("analyst"));
        AgentSpec withSchema = base.withSchemaRef("schema-42");
        AgentSpec withExamples = base.withFewShotRefs(List.of("acme:few/x"));

        assertNotEquals(base.effectiveHash(), withSchema.effectiveHash(),
                "otherwise the candidate eval would resolve the baseline's cached agent");
        assertNotEquals(base.effectiveHash(), withExamples.effectiveHash());
        assertNotEquals(withSchema.effectiveHash(), withExamples.effectiveHash());
        assertTrue(withSchema.effectiveHash().startsWith(base.specHash() + "+"),
                "a candidate id stays readable back to the config it derives from");
    }

    @Test
    void effectiveHash_isDeterministicAndSensitiveToOrderAndValue() {
        AgentSpec base = AgentSpec.root(cfg("analyst"));

        assertEquals(base.withSchemaRef("schema-42").effectiveHash(),
                base.withSchemaRef("schema-42").effectiveHash(), "deterministic");
        assertNotEquals(base.withSchemaRef("schema-42").effectiveHash(),
                base.withSchemaRef("schema-43").effectiveHash(), "a different ref is a different candidate");
        assertNotEquals(base.withFewShotRefs(List.of("a", "b")).effectiveHash(),
                base.withFewShotRefs(List.of("b", "a")).effectiveHash(),
                "the order examples are shown in is part of what runs");
    }

    @Test
    void effectiveHash_coversBothOverlayAxesTogether() {
        AgentSpec base = AgentSpec.root(cfg("analyst"));
        AgentSpec both = base.withSchemaRef("schema-42").withFewShotRefs(List.of("acme:few/x"));

        assertNotEquals(base.withSchemaRef("schema-42").effectiveHash(), both.effectiveHash());
        assertNotEquals(base.withFewShotRefs(List.of("acme:few/x")).effectiveHash(), both.effectiveHash());
    }
}
