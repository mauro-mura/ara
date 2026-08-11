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
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of {@link ApprovalGate}.
 *
 * <p>Each call to {@link #requestApproval} registers the request and returns a
 * {@link CompletableFuture} that is completed by {@link #submit} when the external
 * decision arrives. The calling virtual thread parks cheaply on {@code future.join()}.
 *
 * <p>A shared {@link ScheduledExecutorService} handles timeout expiry: when a
 * request's deadline passes, the future is completed exceptionally with
 * {@link ApprovalTimeoutException} and the pending maps are cleaned up.
 *
 * <p>This class is intended to be used as a singleton by {@code AraRuntime}.
 */
public class InMemoryApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(InMemoryApprovalGate.class);

    private final Map<String, CompletableFuture<ApprovalDecision>> pendingFutures =
            new ConcurrentHashMap<>();
    private final Map<String, ApprovalRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public InMemoryApprovalGate() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("ara-hitl-timeout");
            t.setDaemon(true);
            return t;
        }));
    }

    InMemoryApprovalGate(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");

        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();

        future.whenComplete((decision, ex) -> {
            pendingFutures.remove(request.requestId());
            pendingRequests.remove(request.requestId());
        });

        pendingFutures.put(request.requestId(), future);
        pendingRequests.put(request.requestId(), request);
        scheduleTimeout(request, future);

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

    private void scheduleTimeout(ApprovalRequest request, CompletableFuture<ApprovalDecision> future) {
        long delayMs = Math.max(0L,
                Duration.between(Instant.now(), request.expiresAt()).toMillis());

        scheduler.schedule(() -> {
            boolean timedOut = future.completeExceptionally(new ApprovalTimeoutException(request));
            if (timedOut) {
                log.warn("Approval request timed out: requestId={}, agentId={}, action={}",
                        request.requestId(), request.agentId(), request.action());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
