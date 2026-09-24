package io.ara.runtime.memory;

import io.ara.core.memory.TokenCounter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0087: with an explicit {@link TokenCounter}, {@link SlidingWindowMemoryManager} bases
 * {@link SlidingWindowMemoryManager#estimatedTokens()} and eviction on it instead of the
 * historic {@code chars / 4} estimate. The {@code null}-counter (unchanged) path is already
 * covered exhaustively by {@code SlidingWindowTokenAccountingTest} against a fresh recount —
 * this class only exercises the new branch.
 */
class SlidingWindowMemoryManagerTokenCounterTest {

    /** One "token" per character — trivially easy to hand-verify against. */
    private static final class OneTokenPerCharCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.length();
        }
    }

    @Test
    void estimatedTokens_usesTheSuppliedCounter_notCharsPerFour() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                1_000_000, EvictionPolicy.DROP_MIDDLE, null, null, null, null,
                io.ara.core.telemetry.AraTelemetry.noop(), new OneTokenPerCharCounter());

        m.appendToWorkingMemory("user", "1234567890");   // role "user" (4) + " " (1) + content (10) = 15

        assertEquals(15, m.estimatedTokens(),
                "with a 1-token-per-char counter, estimatedTokens() must equal role+space+content length");
    }

    @Test
    void clearWorkingMemory_resetsTheCounterBackedEstimate() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                1_000_000, EvictionPolicy.DROP_MIDDLE, null, null, null, null,
                io.ara.core.telemetry.AraTelemetry.noop(), new OneTokenPerCharCounter());

        m.appendToWorkingMemory("user", "some content here");
        assertTrue(m.estimatedTokens() > 0);

        m.clearWorkingMemory();

        assertEquals(0, m.estimatedTokens(), "clear must reset the counter-backed estimate too");
    }

    @Test
    void eviction_triggersOnTheSuppliedCounter_evenWhenCharBudgetWouldNotEvict() {
        // Budget of 20 "tokens". Under this counter the two entries below total 33 (16 + 17,
        // role+space+content) and must trigger DROP_OLDEST; under the historic chars/4
        // estimate the same 33 chars would be ~8 tokens and never evict at all — the
        // contrast this test exists to prove.
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                20, EvictionPolicy.DROP_OLDEST, null, null, null, null,
                io.ara.core.telemetry.AraTelemetry.noop(), new OneTokenPerCharCounter());

        m.appendToWorkingMemory("user", "first entry");
        m.appendToWorkingMemory("user", "second entry");

        assertEquals(1, m.workingMemory().size(),
                "the counter-backed budget must have evicted the oldest entry");
        assertEquals("second entry", m.workingMemory().get(0).content());
    }
}
