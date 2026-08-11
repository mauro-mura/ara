package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the prompt/output token split added to {@link ExecutionResult}: the new
 * fine-grained factories store them directly, the legacy single-total factories keep
 * {@link ExecutionResult#tokensUsed()} unchanged by attributing the whole count to
 * {@code outputTokens}, and {@code tokensUsed()} always stays in sync with the sum
 * (it is derived, never stored).
 */
class ExecutionResultTest {

    @Test
    void success_withPromptAndOutputSplit_reportsBothAndTheirSum() {
        ExecutionResult result = ExecutionResult.success("done", 2, 30, 70, java.util.List.of());

        assertTrue(result.isSuccess());
        assertEquals(30, result.promptTokens());
        assertEquals(70, result.outputTokens());
        assertEquals(100, result.tokensUsed());
    }

    @Test
    void failure_withPromptAndOutputSplit_reportsBothAndTheirSum() {
        ExecutionResult result = ExecutionResult.failure("boom", 1, 5, 15, java.util.List.of());

        assertFalse(result.isSuccess());
        assertEquals("boom", result.failureReasonOpt().orElseThrow());
        assertEquals(5, result.promptTokens());
        assertEquals(15, result.outputTokens());
        assertEquals(20, result.tokensUsed());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacySingleTotalFactories_attributeTheWholeCountToOutputTokens() {
        ExecutionResult success = ExecutionResult.success("done", 1, 42);
        ExecutionResult failure = ExecutionResult.failure("boom", 1, 42);

        assertEquals(0, success.promptTokens());
        assertEquals(42, success.outputTokens());
        assertEquals(42, success.tokensUsed());

        assertEquals(0, failure.promptTokens());
        assertEquals(42, failure.outputTokens());
        assertEquals(42, failure.tokensUsed());
    }
}
