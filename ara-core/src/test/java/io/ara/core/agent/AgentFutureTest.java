package io.ara.core.agent;

import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P7 row (ParallelAgent/AgentFuture),
 * §4 U20bis — regression test.
 *
 * <p>{@link AgentFuture#allOf} used to have no way to bound the fan-out at all:
 * {@link AgentFuture#get()} calls {@code delegate.join()}, which never times out on its
 * own, so one permanently-hung member blocked the whole {@code allOf} indefinitely.
 */
class AgentFutureTest {

    private static final AgentId AGENT = AgentId.of("member");

    private static AgentResponse ok(String content) {
        return AgentResponse.success("t1", AGENT, content, 1, 0, 0, Duration.ZERO, List.of());
    }

    @Test
    void allOf_withATimeout_failsPromptlyInsteadOfHangingOnAStuckMember() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch neverCounts = new CountDownLatch(1);
            AgentFuture hung = AgentFuture.async(() -> {
                try {
                    neverCounts.await(30, TimeUnit.SECONDS);   // simulates a permanently stuck member
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ok("should never actually complete within the test");
            }, executor);
            AgentFuture fast = AgentFuture.async(() -> ok("fast"), executor);

            AgentFuture aggregate = AgentFuture.allOf(
                    List.of(hung, fast), AgentChain.MergeStrategy.firstWins(),
                    AgentChain.FailurePolicy.FAIL_FAST, Duration.ofMillis(200));

            // allOf() is exception-safe by design (see its own Javadoc): a timeout becomes
            // a failed AgentResponse, not a thrown exception — get() only throws for an
            // *unrecoverable* infrastructure error, which this is not.
            long start = System.nanoTime();
            AgentResponse response = aggregate.get();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 5_000,
                    "allOf(timeout) must fail well before the member's own 30s wait — took " + elapsedMs + "ms");
            assertTrue(!response.isSuccess() && response.failureReason() != null
                            && response.failureReason().toLowerCase().contains("timeout"),
                    "the failure must be attributable to the timeout: " + response.failureReason());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allOf_withoutATimeout_stillWaitsForEveryMember_unboundedBehaviourUnchanged() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AgentFuture a = AgentFuture.async(() -> ok("a"), executor);
            AgentFuture b = AgentFuture.async(() -> ok("b"), executor);

            AgentResponse result = AgentFuture.allOf(
                    List.of(a, b), AgentChain.MergeStrategy.firstWins(), AgentChain.FailurePolicy.FAIL_FAST).get();

            assertTrue(result.isSuccess());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void allOf_aNullTimeout_behavesExactlyLikeTheNoTimeoutOverload() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AgentFuture a = AgentFuture.async(() -> ok("a"), executor);

            AgentResponse result = AgentFuture.allOf(
                    List.of(a), AgentChain.MergeStrategy.firstWins(), AgentChain.FailurePolicy.FAIL_FAST, null).get();

            assertTrue(result.isSuccess());
        } finally {
            executor.shutdown();
        }
    }
}
