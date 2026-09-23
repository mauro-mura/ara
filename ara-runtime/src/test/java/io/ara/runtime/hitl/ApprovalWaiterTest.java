package io.ara.runtime.hitl;

import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ApprovalWaiter} in isolation from any of its three call sites
 * ({@code ApprovalToolRegistry}, {@code ApprovalClassifier}, {@code ScopeVerifier}) —
 * concurrency hardening P3: the shared replacement for {@code future.join()}, which does
 * not respond to {@link Thread#interrupt()} and has no bound of its own beyond whatever the
 * {@link io.ara.core.hitl.ApprovalGate} implementation enforces.
 */
class ApprovalWaiterTest {

    private static ApprovalRequest request(Duration timeout) {
        return ApprovalRequest.of("agent", "action", "payload", timeout);
    }

    @Test
    void anAlreadyDecidedFuture_returnsImmediately() throws InterruptedException {
        ApprovalDecision decision = new ApprovalDecision.Approved();
        ApprovalDecision result = ApprovalWaiter.await(
                CompletableFuture.completedFuture(decision), request(Duration.ofMinutes(30)));
        assertEquals(decision, result);
    }

    @Test
    void interrupted_cancelsTheFutureAndThrowsPromptly() throws InterruptedException {
        CompletableFuture<ApprovalDecision> neverCompletes = new CompletableFuture<>();
        ApprovalRequest request = request(Duration.ofMinutes(30));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ApprovalWaiter.await(neverCompletes, request);
            } catch (InterruptedException e) {
                thrown.set(e);
            }
        });
        worker.start();
        Thread.sleep(50); // let the worker reach get(...)
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "must not stay parked past the interrupt — join() would have");
        assertTrue(thrown.get() instanceof InterruptedException);
        assertTrue(neverCompletes.isCancelled(), "the pending future must be cancelled, not left dangling");
    }

    @Test
    void theLocalDeadlinePassing_cancelsTheFutureAndThrowsTimeout_evenIfTheGateNeverCompletesIt() {
        // A future that will genuinely never complete — standing in for a custom
        // ApprovalGate implementation that does not enforce its own expiry the way
        // InMemoryApprovalGate does.
        CompletableFuture<ApprovalDecision> neverCompletes = new CompletableFuture<>();
        ApprovalRequest request = request(Duration.ofMillis(50));

        ApprovalTimeoutException e = assertThrows(ApprovalTimeoutException.class,
                () -> ApprovalWaiter.await(neverCompletes, request));
        assertEquals(request.requestId(), e.getRequestId());
        assertTrue(neverCompletes.isCancelled(), "the pending future must be cancelled, not left dangling");
    }

    @Test
    void aRuntimeExceptionFromTheGate_isRethrownUnwrapped() {
        ApprovalRequest request = request(Duration.ofMinutes(30));
        CompletableFuture<ApprovalDecision> failed =
                CompletableFuture.failedFuture(new IllegalStateException("store is down"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ApprovalWaiter.await(failed, request));
        assertEquals("store is down", e.getMessage());
    }

    @Test
    void anApprovalTimeoutExceptionFromTheGateItself_passesThroughUnwrapped() {
        ApprovalRequest request = request(Duration.ofMinutes(30));
        CompletableFuture<ApprovalDecision> failed =
                CompletableFuture.failedFuture(new ApprovalTimeoutException(request));

        assertThrows(ApprovalTimeoutException.class, () -> ApprovalWaiter.await(failed, request));
    }

    @Test
    void aCheckedFailureCause_isWrappedInCompletionException() {
        ApprovalRequest request = request(Duration.ofMinutes(30));
        CompletableFuture<ApprovalDecision> failed =
                CompletableFuture.failedFuture(new java.io.IOException("transport error"));

        CompletionException e = assertThrows(CompletionException.class,
                () -> ApprovalWaiter.await(failed, request));
        assertTrue(e.getCause() instanceof java.io.IOException);
    }

    @Test
    void requestApproval_isDelegatedToTheGate() throws InterruptedException {
        ApprovalDecision decision = new ApprovalDecision.Approved();
        ApprovalRequest request = request(Duration.ofMinutes(30));
        var gate = new io.ara.core.hitl.ApprovalGate() {
            @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest r) {
                return CompletableFuture.completedFuture(decision);
            }
            @Override public void submit(String requestId, ApprovalDecision d) {
                throw new UnsupportedOperationException();
            }
            @Override public java.util.List<ApprovalRequest> getPendingRequests() {
                return java.util.List.of();
            }
        };

        assertEquals(decision, ApprovalWaiter.await(gate, request));
    }
}
