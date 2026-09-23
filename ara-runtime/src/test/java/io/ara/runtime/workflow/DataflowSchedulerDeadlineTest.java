package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P7, §4 U20 — regression tests.
 *
 * <p>{@code DataflowScheduler.drive()} used to have no wall-clock bound at all:
 * {@code completion.take().get()} blocked forever if no node ever finished, and {@code
 * runMapOverChildren}'s {@code future.get()} did the same per child. A single hung node
 * (or mapOver child) meant the whole run — and, before this same phase's fix to {@code
 * WorkflowStrategy}, the {@code try-with-resources} pool's own {@code close()} on top of
 * that — never returned.
 */
class DataflowSchedulerDeadlineTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void aHungNode_failsAtTheDeadline_insteadOfBlockingForever() throws Exception {
        CountDownLatch neverCounts = new CountDownLatch(1);
        WorkflowNode hung = WorkflowNode.of("hung", in -> {
            try {
                neverCounts.await(30, TimeUnit.SECONDS);   // simulates a node that never returns
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "unreachable";
        });
        WorkflowGraph graph = new WorkflowGraph(List.of(hung), List.of());

        Instant deadline = Instant.now().plusMillis(200);
        long start = System.nanoTime();
        WorkflowResult result = new DataflowScheduler(graph, 10, null, deadline).run("start", pool);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.ok());
        assertTrue(result.failureReason().toLowerCase().contains("deadline"),
                "failure reason should name the deadline: " + result.failureReason());
        assertTrue(elapsedMs < 5_000,
                "the run must fail well before the node's own 30s wait — took " + elapsedMs + "ms");
    }

    /**
     * Verifies the combined, observable guarantee — the whole run does not hang — not
     * {@code runMapOverChildren}'s own per-child bound in isolation: {@code drive()}'s
     * {@code awaitNext} already abandons a hung {@code fire()} call (which is what runs
     * {@code runMapOverChildren}) at the same deadline regardless, so this test alone
     * cannot tell the two fixes apart (confirmed: it still passes with only {@code
     * runMapOverChildren}'s own bound reverted). {@code runMapOverChildren}'s bound still
     * has independent value this test does not exercise: without it, the pool worker
     * thread actually running the hung child leaks forever even after {@code drive()} has
     * moved on — abandoned by the caller, but never itself unblocked.
     */
    @Test
    void aHungMapOverChild_failsAtTheDeadline_insteadOfBlockingForever() throws Exception {
        CountDownLatch neverCounts = new CountDownLatch(1);
        WorkflowGraph graph = new WorkflowGraph(
                List.of(WorkflowNode.of("source", in -> in)), List.of());
        WorkflowNode source = graph.node("source").withMapOver(new WorkflowNode.MapOverSpec(
                "worker",
                out -> List.of("a", "b"),
                elem -> {
                    if (elem.equals("b")) {
                        try {
                            neverCounts.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return "done:" + elem;
                },
                null, 10, AgentChain.FailurePolicy.PARTIAL_OK));
        WorkflowGraph graphWithMapOver = new WorkflowGraph(List.of(source), List.of());

        Instant deadline = Instant.now().plusMillis(200);
        long start = System.nanoTime();
        WorkflowResult result = new DataflowScheduler(graphWithMapOver, 10, null, deadline).run("start", pool);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 5_000,
                "the run must not block on the hung mapOver child — took " + elapsedMs + "ms");
    }

    @Test
    void withoutADeadline_stillWaitsIndefinitely_unboundedBehaviourUnchanged() {
        WorkflowNode fast = WorkflowNode.of("fast", in -> "done");
        WorkflowGraph graph = new WorkflowGraph(List.of(fast), List.of());

        WorkflowResult result = new DataflowScheduler(graph, 10).run("start", pool);

        assertTrue(result.ok());
    }
}
