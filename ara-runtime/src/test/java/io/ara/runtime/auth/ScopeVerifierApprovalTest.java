package io.ara.runtime.auth;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.hitl.InMemoryApprovalGate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 7 (S4) —
 * {@link ScopeVerifier#checkApproved}. Reuses the existing ADR-048 {@link ApprovalGate}
 * rather than a parallel mechanism — see the method's own javadoc for why.
 */
class ScopeVerifierApprovalTest {

    private static AraAgent agentRequiringApproval(boolean requiresApproval) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .requiresApproval(requiresApproval).build();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void noGateConfigured_isANoOp_evenIfTheAgentRequiresApproval() {
        AraAgent agent = agentRequiringApproval(true);
        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, null, "caller", ScopeSet.EMPTY));
    }

    @Test
    void agentDoesNotRequireApproval_isANoOp_evenWithAGateConfigured() {
        AraAgent agent = agentRequiringApproval(false);
        ApprovalGate gate = new InMemoryApprovalGate();
        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.EMPTY));
    }

    @Test
    void approved_letsTheCallProceed() throws InterruptedException {
        AraAgent agent = agentRequiringApproval(true);
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        Thread operator = approveFirstPendingAfter(gate, Duration.ofMillis(50));
        operator.start();

        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.of("ops")));
        operator.join();
    }

    @Test
    void modified_alsoLetsTheCallProceed_thisIsAnAuthorizationGateNotAPayloadRewrite() throws InterruptedException {
        AraAgent agent = agentRequiringApproval(true);
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        Thread operator = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) {
                gate.submit(pending.get(0).requestId(), new ApprovalDecision.Modified("irrelevant-here"));
            }
        });
        operator.start();

        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.of("ops")));
        operator.join();
    }

    @Test
    void rejected_throwsApprovalRequired() throws InterruptedException {
        AraAgent agent = agentRequiringApproval(true);
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        Thread operator = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) {
                gate.submit(pending.get(0).requestId(), new ApprovalDecision.Rejected("not today"));
            }
        });
        operator.start();

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.of("ops")));
        assertEquals(AuthorizationException.Reason.APPROVAL_REQUIRED, e.reason());
        operator.join();
    }

    /**
     * P3 hardening: {@code join()} used to ignore an interrupt entirely, leaving the
     * calling thread parked until the gate's own timeout (up to its full configured
     * duration — 30 minutes by default) instead of reacting to cancellation immediately.
     */
    @Test
    void interrupted_isNoticedImmediately_notAfterTheFullTimeout_andThrowsApprovalRequired() throws InterruptedException {
        AraAgent agent = agentRequiringApproval(true);
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        java.util.concurrent.atomic.AtomicReference<AuthorizationException> caught = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.of("ops"));
            } catch (AuthorizationException e) {
                caught.set(e);
            }
        });
        worker.start();

        for (int i = 0; i < 200 && gate.getPendingRequests().isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertFalse(gate.getPendingRequests().isEmpty(), "no approval request was registered within 2s");

        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "the worker must not stay parked past the interrupt");
        assertEquals(AuthorizationException.Reason.APPROVAL_REQUIRED, caught.get().reason());
        assertTrue(gate.getPendingRequests().isEmpty(), "the cancelled request must be dropped from the gate");
    }

    @Test
    void gateFailure_throwsApprovalRequired_failsClosed() {
        AraAgent agent = agentRequiringApproval(true);
        ApprovalGate failingGate = new ApprovalGate() {
            @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
                return CompletableFuture.failedFuture(new RuntimeException("gate is down"));
            }
            @Override public void submit(String requestId, ApprovalDecision decision) {
                throw new UnsupportedOperationException();
            }
            @Override public List<ApprovalRequest> getPendingRequests() { return List.of(); }
        };

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkApproved(agent, failingGate, "caller", ScopeSet.of("ops")));
        assertEquals(AuthorizationException.Reason.APPROVAL_REQUIRED, e.reason());
    }

    private static Thread approveFirstPendingAfter(InMemoryApprovalGate gate, Duration delay) {
        return new Thread(() -> {
            try { Thread.sleep(delay.toMillis()); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) {
                gate.submit(pending.get(0).requestId(), new ApprovalDecision.Approved());
            }
        });
    }
}
