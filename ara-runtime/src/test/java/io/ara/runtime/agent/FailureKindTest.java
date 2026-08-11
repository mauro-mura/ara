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
