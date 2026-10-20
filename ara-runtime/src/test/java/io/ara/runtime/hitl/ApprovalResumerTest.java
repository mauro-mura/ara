package io.ara.runtime.hitl;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.PendingApproval;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalResumerTest {

    /** A gate that hands out futures the test completes itself. */
    private static final class ManualGate implements ApprovalGate {
        final List<ApprovalRequest> registered = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<ApprovalDecision>> futures = new CopyOnWriteArrayList<>();

        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            registered.add(request);
            CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }
        @Override public void submit(String requestId, ApprovalDecision decision) { throw new UnsupportedOperationException(); }
        @Override public List<ApprovalRequest> getPendingRequests() { return List.copyOf(registered); }
    }

    private static final ApprovalRequest FIRST = ApprovalRequest.of("agent-1", "pay", "{\"a\":1}", Duration.ofMinutes(5));
    private static final ApprovalRequest SECOND = ApprovalRequest.of("agent-1", "pay", "{\"a\":2}", Duration.ofMinutes(5));

    private static RunCheckpoint suspendedOn(ApprovalRequest... requests) {
        List<PendingApproval.ToolCall> calls = java.util.Arrays.stream(requests)
                .map(r -> new PendingApproval.ToolCall(r.action(), (String) r.payload(), null, r)).toList();
        return new RunCheckpoint(SessionId.of("s1"), "task-1", "in", 1, null, null, 0, 0,
                Money.zero("EUR"), new PendingApproval(calls));
    }

    /** An agent that records the decisions it was resumed with. */
    private static final class RecordingAgent {
        final List<AgentTask> resumedWith = new CopyOnWriteArrayList<>();
        final AraAgent agent = AraAgents.deterministic(AgentId.of("agent-1"), task -> {
            resumedWith.add(task);
            return "resumed";
        });
    }

    @Test
    void watching_registersEveryRequestOfTheTurnWithTheGate() {
        ManualGate gate = new ManualGate();
        ApprovalResumer resumer = new ApprovalResumer(gate, CheckpointStore.inMemory(), id -> Optional.empty(), r -> { });

        resumer.watch(suspendedOn(FIRST, SECOND));

        assertEquals(List.of(FIRST, SECOND), gate.registered);
    }

    @Test
    void theRunResumes_onlyOnceEveryRequestOfTheTurnIsAnswered() throws Exception {
        ManualGate gate = new ManualGate();
        RecordingAgent recording = new RecordingAgent();
        CompletableFuture<AgentResponse> resumed = new CompletableFuture<>();
        ApprovalResumer resumer = new ApprovalResumer(gate, CheckpointStore.inMemory(),
                id -> Optional.of(recording.agent), resumed::complete);

        resumer.watch(suspendedOn(FIRST, SECOND));
        gate.futures.get(0).complete(new ApprovalDecision.Approved());
        Thread.sleep(150);
        assertTrue(recording.resumedWith.isEmpty(), "one answer out of two must not resume the run");

        gate.futures.get(1).complete(new ApprovalDecision.Rejected("no"));
        AgentResponse response = resumed.get(5, TimeUnit.SECONDS);

        assertEquals("resumed", response.content());
        AgentTask task = recording.resumedWith.get(0);
        assertEquals("task-1", task.taskId());
        assertEquals(new ApprovalDecision.Approved(), task.resumeDecisions().get(FIRST.requestId()));
        assertEquals(new ApprovalDecision.Rejected("no"), task.resumeDecisions().get(SECOND.requestId()));
    }

    @Test
    void anExpiredRequest_isAnsweredAsARefusal_soTheRunStillContinues() throws Exception {
        ManualGate gate = new ManualGate();
        RecordingAgent recording = new RecordingAgent();
        CompletableFuture<AgentResponse> resumed = new CompletableFuture<>();
        ApprovalResumer resumer = new ApprovalResumer(gate, CheckpointStore.inMemory(),
                id -> Optional.of(recording.agent), resumed::complete);

        resumer.watch(suspendedOn(FIRST));
        gate.futures.get(0).completeExceptionally(new ApprovalTimeoutException(FIRST));
        resumed.get(5, TimeUnit.SECONDS);

        ApprovalDecision decision = recording.resumedWith.get(0).resumeDecisions().get(FIRST.requestId());
        assertInstanceOf(ApprovalDecision.Rejected.class, decision);
        assertTrue(((ApprovalDecision.Rejected) decision).reason().contains("timed out"));
    }

    @Test
    void aRunWatchedTwice_isRegisteredOnce() {
        ManualGate gate = new ManualGate();
        CheckpointStore store = CheckpointStore.inMemory();
        RunCheckpoint checkpoint = suspendedOn(FIRST);
        store.save(checkpoint);
        ApprovalResumer resumer = new ApprovalResumer(gate, store, id -> Optional.empty(), r -> { });

        resumer.watch(checkpoint);   // live suspension
        resumer.recover();           // and the same run found again in the store

        assertEquals(1, gate.registered.size());
    }

    @Test
    void recover_putsBackTheQuestionsOfEverySuspendedRunInTheStore() {
        ManualGate gate = new ManualGate();
        CheckpointStore store = CheckpointStore.inMemory();
        store.save(suspendedOn(FIRST));
        ApprovalResumer resumer = new ApprovalResumer(gate, store, id -> Optional.empty(), r -> { });

        resumer.recover();

        assertEquals(List.of(FIRST), gate.registered);
    }

    @Test
    void anAgentThatIsGone_isLoggedNotThrown_andItsCheckpointStays() throws Exception {
        ManualGate gate = new ManualGate();
        CheckpointStore store = CheckpointStore.inMemory();
        RunCheckpoint checkpoint = suspendedOn(FIRST);
        store.save(checkpoint);
        AtomicInteger lookups = new AtomicInteger();
        ApprovalResumer resumer = new ApprovalResumer(gate, store,
                id -> { lookups.incrementAndGet(); return Optional.empty(); }, r -> { throw new AssertionError("no run"); });

        resumer.watch(checkpoint);
        gate.futures.get(0).complete(new ApprovalDecision.Approved());
        long deadline = System.currentTimeMillis() + 5000;
        while (lookups.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);

        assertEquals(1, lookups.get(), "the agent is looked up when the decision arrives");
        assertTrue(store.loadLatest(checkpoint.sessionId(), checkpoint.taskId()).isPresent(),
                "nothing is lost: a later restart can still resume it");
    }

    @Test
    void aCheckpointThatWaitsForNothing_isRefused() {
        ApprovalResumer resumer = new ApprovalResumer(new ManualGate(), CheckpointStore.inMemory(),
                id -> Optional.empty(), r -> { });
        RunCheckpoint ordinary = new RunCheckpoint(SessionId.of("s1"), "t", "in", 1, null, null, 0, 0, Money.zero("EUR"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> resumer.watch(ordinary));
    }
}
