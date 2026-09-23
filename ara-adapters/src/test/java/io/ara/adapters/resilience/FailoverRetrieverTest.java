package io.ara.adapters.resilience;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.ara.core.retriever.RetrievedChunk;
import io.ara.core.retriever.Retriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailoverRetrieverTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();   // test hygiene: never leak an interrupt into the next test
    }

    private static Retriever returning(List<RetrievedChunk> chunks) {
        return (query, maxResults) -> chunks;
    }

    private static Retriever failingWith(RuntimeException ex) {
        return (query, maxResults) -> { throw ex; };
    }

    private static RetrievedChunk chunk(String docId) {
        return new RetrievedChunk(docId, "title", "content", 0.9);
    }

    @Test
    void retrieve_returnsPrimaryResult_whenPrimarySucceeds() {
        Retriever primary   = returning(List.of(chunk("a")));
        Retriever secondary = failingWith(new RuntimeException("must not be used"));

        FailoverRetriever failover = new FailoverRetriever(List.of(primary, secondary));

        assertEquals(List.of(chunk("a")), failover.retrieve("q", 5));
    }

    @Test
    void retrieve_failsOverToNextCandidate_onException() {
        Retriever primary   = failingWith(new RuntimeException("qdrant down"));
        Retriever secondary = returning(List.of(chunk("b")));

        FailoverRetriever failover = new FailoverRetriever(List.of(primary, secondary));

        assertEquals(List.of(chunk("b")), failover.retrieve("q", 5));
    }

    @Test
    void retrieve_doesNotFailOver_onAnEmptyResult() {
        // A healthy primary with no hits must NOT trigger the fallback — an empty answer is a
        // correct answer, and treating it as a failure would make a real outage indistinguishable
        // from a query nobody has indexed yet.
        AtomicInteger secondaryCalls = new AtomicInteger();
        Retriever primary = returning(List.of());
        Retriever secondary = (query, maxResults) -> {
            secondaryCalls.incrementAndGet();
            return List.of(chunk("should-not-be-reached"));
        };

        FailoverRetriever failover = new FailoverRetriever(List.of(primary, secondary));
        List<RetrievedChunk> result = failover.retrieve("q", 5);

        assertTrue(result.isEmpty());
        assertEquals(0, secondaryCalls.get(), "an empty primary result must not trigger the fallback");
    }

    @Test
    void retrieve_throwsLastFailure_whenEveryCandidateFails() {
        Retriever primary   = failingWith(new RuntimeException("first down"));
        Retriever secondary = failingWith(new RuntimeException("second down"));

        FailoverRetriever failover = new FailoverRetriever(List.of(primary, secondary));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> failover.retrieve("q", 5));
        assertEquals("second down", ex.getMessage());
    }

    @Test
    void constructor_rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new FailoverRetriever(List.of()));
    }

    // ── P8/U21 — interrupt stops the loop instead of marching through every candidate ──

    @Test
    void retrieve_stopsTheLoopAfterAnInterruptedCandidate_doesNotTryTheRemainingOnes() {
        AtomicInteger callsB = new AtomicInteger();
        AtomicInteger callsC = new AtomicInteger();
        Retriever a = failingWith(new RuntimeException("a down"));
        Retriever b = (query, maxResults) -> { callsB.incrementAndGet(); throw new RuntimeException("b down"); };
        Retriever c = (query, maxResults) -> { callsC.incrementAndGet(); return List.of(chunk("should-not-be-reached")); };

        // Simulates a deadline watchdog firing while candidate 'a' was running: by the
        // time retrieve() gets control back, the calling thread is already interrupted.
        Thread.currentThread().interrupt();

        FailoverRetriever failover = new FailoverRetriever(List.of(a, b, c));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> failover.retrieve("q", 5));

        assertEquals("a down", ex.getMessage(), "must throw the first (only tried) candidate's failure");
        assertEquals(0, callsB.get(), "the loop must stop after the interrupted check — 'b' must never run");
        assertEquals(0, callsC.get(), "'c' must never run either");
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag itself must be left alone, not swallowed");
    }

    @Test
    void retrieve_uninterrupted_stillFailsOverNormally() {
        Retriever primary   = failingWith(new RuntimeException("qdrant down"));
        Retriever secondary = returning(List.of(chunk("b")));

        FailoverRetriever failover = new FailoverRetriever(List.of(primary, secondary));

        assertEquals(List.of(chunk("b")), failover.retrieve("q", 5));
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
