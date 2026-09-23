package io.ara.runtime.hitl;

import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared wait logic for every call site that parks a caller on an
 * {@link ApprovalGate#requestApproval(ApprovalRequest)} future (ADR-0056, ADR-033 Fase 7).
 *
 * <p>Concurrency hardening P3: {@code future.join()} does not respond to
 * {@link Thread#interrupt()} — a cancelled task stays parked until the gate's own timeout
 * fires, up to its full configured duration (30 minutes by default). {@link #await} uses
 * {@link CompletableFuture#get(long, TimeUnit)} instead, bounded by {@code request.expiresAt()}
 * so an interrupt is observed immediately, and as a second, independent bound in case a custom
 * {@link ApprovalGate} implementation does not enforce the expiry itself the way
 * {@link InMemoryApprovalGate} does. On interrupt or on the local deadline passing, the future
 * is cancelled so the gate can drop the now-unwanted pending request instead of completing it
 * later into nothing.
 */
public final class ApprovalWaiter {

    private ApprovalWaiter() {}

    /**
     * @throws InterruptedException      the calling thread was interrupted while waiting; the
     *                                    interrupt flag is left cleared, matching
     *                                    {@link CompletableFuture#get(long, TimeUnit)}'s own
     *                                    contract — callers that care must restore it themselves
     * @throws ApprovalTimeoutException  neither a decision nor the gate's own timeout arrived
     *                                    before {@code request.expiresAt()}
     */
    public static ApprovalDecision await(ApprovalGate gate, ApprovalRequest request) throws InterruptedException {
        return await(gate.requestApproval(request), request);
    }

    public static ApprovalDecision await(CompletableFuture<ApprovalDecision> future, ApprovalRequest request)
            throws InterruptedException {
        long waitMs = Math.max(0L, Duration.between(Instant.now(), request.expiresAt()).toMillis());
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ApprovalTimeoutException(request);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er) throw er;
            throw new CompletionException(cause);
        }
    }
}
