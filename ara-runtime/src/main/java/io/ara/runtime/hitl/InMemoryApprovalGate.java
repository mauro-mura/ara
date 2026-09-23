package io.ara.runtime.hitl;

import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of {@link ApprovalGate}.
 *
 * <p>Each call to {@link #requestApproval} registers the request and returns a
 * {@link CompletableFuture} that is completed by {@link #submit} when the external
 * decision arrives. The calling virtual thread parks cheaply on it (see
 * {@code io.ara.runtime.hitl.ApprovalWaiter}, the interruptible wait every call site uses
 * instead of {@code join()}).
 *
 * <p>A shared {@link ScheduledExecutorService} handles timeout expiry: when a
 * request's deadline passes, the future is completed exceptionally with
 * {@link ApprovalTimeoutException} and the pending maps are cleaned up. However the future
 * completes — a decision, a timeout, or an external cancellation — the still-pending
 * timeout task is cancelled too, so it never lingers in the scheduler's queue past that
 * point.
 *
 * <p>This class is intended to be used as a singleton by {@code AraRuntime}.
 *
 * <p><b>Lifecycle (P4/U12):</b> the no-arg constructor creates and owns its own
 * scheduler thread — call {@link #close()} when done with this gate, or it leaks for the
 * JVM's lifetime. {@code AraRuntime.stop()} does <em>not</em> call it: the gate is always
 * caller-supplied (via {@code AraRuntime.Builder#approvalGate}, never constructed by
 * {@code AraRuntime} itself — verified: no {@code new InMemoryApprovalGate()} exists
 * anywhere in {@code AraRuntime}/{@code AgentFactory}), the same way {@code sessionStore},
 * {@code mediaStore}, {@code toolRegistry} and every other externally-supplied collaborator
 * is never closed by {@code stop()} either. A gate is also explicitly documented (see
 * {@code Builder#approvalGate}'s own javadoc) to be shared with an external surface — an
 * HTTP gateway, a CLI — that lists and resolves pending approvals independently of any one
 * {@code AraRuntime} instance's lifetime; closing it out from under that surface on
 * {@code stop()} would be a correctness bug, not a cleanup. The caller that constructs a
 * gate is the one that must close it, e.g. in its own shutdown sequence or via
 * try-with-resources.
 */
public class InMemoryApprovalGate implements ApprovalGate, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryApprovalGate.class);

    private final Map<String, CompletableFuture<ApprovalDecision>> pendingFutures =
            new ConcurrentHashMap<>();
    private final Map<String, ApprovalRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    public InMemoryApprovalGate() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("ara-hitl-timeout");
            t.setDaemon(true);
            return t;
        }), true);
    }

    /** Creates a gate using a caller-supplied, caller-owned scheduler (not shut down by {@link #close()}). */
    InMemoryApprovalGate(ScheduledExecutorService scheduler) {
        this(scheduler, false);
    }

    private InMemoryApprovalGate(ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.scheduler     = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    @Override
    public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");

        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();

        pendingFutures.put(request.requestId(), future);
        pendingRequests.put(request.requestId(), request);

        // Captured so a decision (or a cancellation — ApprovalWaiter cancels this future
        // on the caller's interrupt) can cancel the still-pending timeout task instead of
        // leaving it sitting in the scheduler's queue for up to the full expiry window.
        ScheduledFuture<?> timeoutTask = scheduleTimeout(request, future);
        future.whenComplete((decision, ex) -> {
            timeoutTask.cancel(false);
            pendingFutures.remove(request.requestId());
            pendingRequests.remove(request.requestId());
        });

        log.debug("Approval requested: requestId={}, agentId={}, action={}, expiresAt={}",
                request.requestId(), request.agentId(), request.action(), request.expiresAt());

        return future;
    }

    @Override
    public void submit(String requestId, ApprovalDecision decision) {
        CompletableFuture<ApprovalDecision> future = pendingFutures.get(requestId);
        if (future == null) {
            throw new IllegalArgumentException(
                    "No pending approval request found for requestId: " + requestId);
        }
        boolean completed = future.complete(decision);
        if (completed) {
            log.debug("Approval decision submitted: requestId={}, decision={}",
                    requestId, decision.getClass().getSimpleName());
        } else {
            log.debug("Decision ignored — future already completed for requestId={}", requestId);
        }
    }

    @Override
    public List<ApprovalRequest> getPendingRequests() {
        return List.copyOf(pendingRequests.values());
    }

    /**
     * Shuts down the internal timeout scheduler, if this gate created its own (the no-arg
     * constructor) — a no-op for a gate built with a caller-supplied scheduler. Does not
     * resolve any still-pending request: those simply stop being able to time out on their
     * own past this point, the same residual behavior a caller-supplied scheduler already
     * had if the caller shut it down independently.
     */
    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private ScheduledFuture<?> scheduleTimeout(ApprovalRequest request, CompletableFuture<ApprovalDecision> future) {
        long delayMs = Math.max(0L,
                Duration.between(Instant.now(), request.expiresAt()).toMillis());

        return scheduler.schedule(() -> {
            boolean timedOut = future.completeExceptionally(new ApprovalTimeoutException(request));
            if (timedOut) {
                log.warn("Approval request timed out: requestId={}, agentId={}, action={}",
                        request.requestId(), request.agentId(), request.action());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
