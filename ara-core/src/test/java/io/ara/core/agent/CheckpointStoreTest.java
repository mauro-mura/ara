package io.ara.core.agent;

import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointStoreTest {

    private static RunCheckpoint at(String session, String task, int iteration) {
        return new RunCheckpoint(SessionId.of(session), task, "in", iteration, null, null, 0, 0, Money.zero("EUR"));
    }

    @Test
    void inMemory_saveThenLoadLatest_roundTrips() {
        CheckpointStore store = CheckpointStore.inMemory();
        RunCheckpoint checkpoint = at("s1", "t1", 2);

        store.save(checkpoint);

        assertEquals(checkpoint, store.loadLatest(SessionId.of("s1"), "t1").orElseThrow());
    }

    @Test
    void inMemory_loadLatest_unknownRun_returnsEmpty() {
        assertTrue(CheckpointStore.inMemory().loadLatest(SessionId.of("s1"), "never-seen").isEmpty());
    }

    @Test
    void inMemory_save_replacesPreviousCheckpointOfTheSameRun() {
        CheckpointStore store = CheckpointStore.inMemory();

        store.save(at("s1", "t1", 1));
        store.save(at("s1", "t1", 4));

        assertEquals(4, store.loadLatest(SessionId.of("s1"), "t1").orElseThrow().iteration());
    }

    @Test
    void inMemory_runsAreKeyedBySessionAndTask() {
        CheckpointStore store = CheckpointStore.inMemory();

        store.save(at("s1", "t1", 1));
        store.save(at("s1", "t2", 2));
        store.save(at("s2", "t1", 3));

        assertEquals(1, store.loadLatest(SessionId.of("s1"), "t1").orElseThrow().iteration());
        assertEquals(2, store.loadLatest(SessionId.of("s1"), "t2").orElseThrow().iteration());
        assertEquals(3, store.loadLatest(SessionId.of("s2"), "t1").orElseThrow().iteration());
    }

    @Test
    void inMemory_delete_removesOnlyThatRun() {
        CheckpointStore store = CheckpointStore.inMemory();
        store.save(at("s1", "t1", 1));
        store.save(at("s1", "t2", 2));

        store.delete(SessionId.of("s1"), "t1");
        store.delete(SessionId.of("s1"), "never-seen");   // no-op, must not throw

        assertTrue(store.loadLatest(SessionId.of("s1"), "t1").isEmpty());
        assertTrue(store.loadLatest(SessionId.of("s1"), "t2").isPresent());
    }

    @Test
    void inMemory_awaitingApproval_listsOnlyTheRunsSuspendedForAnApproval() {
        CheckpointStore store = CheckpointStore.inMemory();
        io.ara.core.hitl.ApprovalRequest request = io.ara.core.hitl.ApprovalRequest.of(
                "agent", "pay", "{}", java.time.Duration.ofMinutes(1));
        PendingApproval pending = new PendingApproval(java.util.List.of(
                new PendingApproval.ToolCall("pay", "{}", null, request)));
        store.save(at("s1", "ordinary", 1));
        store.save(new RunCheckpoint(SessionId.of("s1"), "suspended", "in", 1, null, null, 0, 0,
                Money.zero("EUR"), pending));

        assertEquals(1, store.awaitingApproval().size());
        assertEquals("suspended", store.awaitingApproval().get(0).taskId());

        store.delete(SessionId.of("s1"), "suspended");
        assertTrue(store.awaitingApproval().isEmpty());
    }

    @Test
    void noop_discardsWrites_andAlwaysReadsEmpty() {
        CheckpointStore store = CheckpointStore.noop();

        store.save(at("s1", "t1", 1));
        store.delete(SessionId.of("s1"), "t1");

        assertTrue(store.loadLatest(SessionId.of("s1"), "t1").isEmpty());
        assertTrue(store.awaitingApproval().isEmpty());
    }
}
