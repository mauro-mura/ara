package io.ara.runtime.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down the failure-reason → {@link FailureKind} mapping.
 *
 * <p>The mapping is text-based, so it is only as good as its agreement with the reason
 * strings {@code AgentInstance} and the built-in strategies actually produce. Testing it
 * directly (rather than only through the {@code agent.failure_kind} span attribute) is what
 * makes a reworded reason show up as a failing test instead of a silent slide into
 * {@link FailureKind#OTHER}.
 */
class FailureKindTest {

    @ParameterizedTest
    @CsvSource({
            "Session busy: another request is already being processed on session 's1', SESSION_BUSY",
            "Cancelled,                                                                CANCELLED",
            "Agent terminated before execution,                                        CANCELLED",
            "Execution exceeded timeout of 5m,                                         TIMEOUT",
            "Cost budget of $1.00 exhausted,                                           BUDGET_EXCEEDED",
            "Unexpected error: boom,                                                   UNEXPECTED_ERROR",
    })
    void classifiesTheReasonsTheRuntimeProduces(String reason, FailureKind expected) {
        assertEquals(expected, FailureKind.classify(reason));
    }

    @Test
    void maxIterations_isMatchedAnywhereInTheReason_notOnlyAsAPrefix() {
        assertEquals(FailureKind.MAX_ITERATIONS,
                FailureKind.classify("Max iterations (10) reached without a final answer"));
        assertEquals(FailureKind.MAX_ITERATIONS,
                FailureKind.classify("ReAct: Max iterations reached"));
    }

    // ── the typed route: a contract refusal ───────────────────────────────────

    private static io.ara.core.agent.AgentResponse refused(io.ara.core.agent.ContractViolation.Phase phase, String reason) {
        return io.ara.core.agent.AgentResponse.failure("t", io.ara.core.common.AgentId.of("a"), reason,
                java.time.Duration.ZERO).withViolation(new io.ara.core.agent.ContractViolation(phase, java.util.List.of()));
    }

    @Test
    void aContractRefusalIsKindedByItsPhase_whateverItsReasonSays() {
        assertEquals(FailureKind.CONTRACT_REQUEST_VIOLATION,
                FailureKind.of(refused(io.ara.core.agent.ContractViolation.Phase.INPUT, "reworded beyond recognition")));
        assertEquals(FailureKind.CONTRACT_REQUEST_VIOLATION,
                FailureKind.of(refused(io.ara.core.agent.ContractViolation.Phase.MEDIA, "x")));
        assertEquals(FailureKind.CONTRACT_OUTPUT_VIOLATION,
                FailureKind.of(refused(io.ara.core.agent.ContractViolation.Phase.OUTPUT, "x")));
    }

    @Test
    void aFailureWithoutAViolation_isKindedByItsReason_asBefore() {
        io.ara.core.agent.AgentResponse timedOut = io.ara.core.agent.AgentResponse.failure("t",
                io.ara.core.common.AgentId.of("a"), "Execution exceeded timeout of 5m", java.time.Duration.ZERO);

        assertEquals(FailureKind.TIMEOUT, FailureKind.of(timedOut));
    }

    @Test
    void theContractKinds_areNeverProducedByTheTextClassifier() {
        // classify() reads only text; "Contract output violation: …" is exactly what a refusal's reason says,
        // and it must still not be called a contract kind from the text — only the typed violation may.
        assertEquals(FailureKind.OTHER, FailureKind.classify("Contract output violation: Schema violations (1): $.a: x"));
        assertEquals(FailureKind.OTHER, FailureKind.classify("Contract input violation: x"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "something nobody planned for", "session busy"})
    void unrecognisedReasonsFallBackToOther_ratherThanGuessing(String reason) {
        assertEquals(FailureKind.OTHER, FailureKind.classify(reason),
                "classification is case-sensitive and prefix-anchored on purpose — no fuzzy guessing");
    }

    @Test
    void classificationNeverLeaksTheRawReasonText() {
        // The label is what lands on an always-on telemetry span, so it must stay bounded
        // even when the reason embeds an arbitrary exception message.
        String reason = "Unexpected error: user=alice token=sk-secret-value";
        assertEquals(FailureKind.UNEXPECTED_ERROR, FailureKind.classify(reason));
        assertEquals("UNEXPECTED_ERROR", FailureKind.classify(reason).name());
    }
}
