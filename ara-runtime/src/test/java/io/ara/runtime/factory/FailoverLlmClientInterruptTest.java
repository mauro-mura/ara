package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P8 row (FailoverLlmClient), §4 U21bis
 * — regression test.
 *
 * <p>{@code FailoverLlmClient.complete()} used to have no interrupt check anywhere in the
 * failover loop: a deadline watchdog ({@code ReactExecutionSupport.completeWithin})
 * interrupting the calling thread mid-candidate did not stop the loop from marching
 * through every remaining candidate, each paying its own full timeout — turning a single
 * deadline overrun into an N×timeout amplification.
 */
class FailoverLlmClientInterruptTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();   // test hygiene: never leak an interrupt into the next test
    }

    /** A client whose failure is failover-eligible (shouldFailover() == true). */
    private static LlmClient failingClient(String id, AtomicInteger calls) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                calls.incrementAndGet();
                throw LlmException.networkError(id, "boom", null);
            }
            @Override public String providerId() { return id; }
        };
    }

    @Test
    void complete_stopsTheLoopAfterAnInterruptedCandidate_doesNotTryTheRemainingOnes() {
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();
        AtomicInteger callsC = new AtomicInteger();
        LlmClient a = failingClient("a", callsA);
        LlmClient b = failingClient("b", callsB);
        LlmClient c = failingClient("c", callsC);

        // Simulates a deadline watchdog firing while candidate 'a' was running: by the
        // time complete() gets control back, the calling thread is already interrupted.
        Thread.currentThread().interrupt();

        FailoverLlmClient failover = new FailoverLlmClient(List.of(a, b, c));
        assertThrows(LlmException.class, () -> failover.complete(List.of(), (LlmCallContext) null));

        assertEquals(1, callsA.get(), "the first candidate must still be tried");
        assertEquals(0, callsB.get(), "the loop must stop after the interrupted check — 'b' must never run");
        assertEquals(0, callsC.get(), "'c' must never run either");
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag itself must be left alone, not swallowed");
    }

    @Test
    void complete_uninterrupted_stillTriesEveryCandidateInOrder() {
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();
        LlmClient a = failingClient("a", callsA);
        LlmClient b = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                callsB.incrementAndGet();
                return new LlmCompletion("ok", 1, 1, "stop", null);
            }
            @Override public String providerId() { return "b"; }
        };

        FailoverLlmClient failover = new FailoverLlmClient(List.of(a, b));
        LlmCompletion result = failover.complete(List.of(), (LlmCallContext) null);

        assertEquals("ok", result.text());
        assertEquals(1, callsA.get());
        assertEquals(1, callsB.get(), "uninterrupted failover must still try the next candidate normally");
    }
}
