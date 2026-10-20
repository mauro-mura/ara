package io.ara.core.agent;

import java.util.List;
import java.util.Optional;

/**
 * Durable backing for {@link RunCheckpoint}s, keyed by session and task. Owned by the
 * runtime, like {@link SessionStore}: where a suspended run is kept is an infrastructure
 * decision, not per-agent configuration.
 *
 * <p>Only the latest checkpoint of a run is kept. A run is resumed from where it last
 * stopped, never from an earlier point, so a history would be storage nobody reads;
 * {@link #save} therefore replaces whatever was stored for the same run.
 *
 * <p>Always present, never {@code null}: {@link #noop()} is the default — writes
 * discarded, reads always empty — so callers never branch on "is a store configured", the
 * same null-object idiom as {@link SessionStore#noop()}. {@link #inMemory()} is a
 * process-local reference implementation: it survives a suspension but not a JVM restart,
 * which is what a durable implementation, in its own module, adds.
 *
 * <p><b>Every method here must not block on I/O</b>, for the reason spelled out on {@link
 * SessionStore}: callers may hold a lock across the call, and a virtual thread blocked on
 * real I/O under a lock pins its carrier on JDK 21. A durable implementation does its I/O
 * off the calling thread.
 */
public interface CheckpointStore {

    /** Stores {@code checkpoint}, replacing any earlier one for the same session and task. */
    void save(RunCheckpoint checkpoint);

    /** The checkpoint stored for this run, or empty when there is none. */
    Optional<RunCheckpoint> loadLatest(SessionId sessionId, String taskId);

    /** Removes the checkpoint for this run; does nothing when there is none. */
    void delete(SessionId sessionId, String taskId);

    /**
     * Every stored run that stopped before a turn's tool calls and is waiting for a human to
     * decide (ADR-048 D2): the checkpoints whose {@link RunCheckpoint#pendingApproval()} is set.
     * What a restarted runtime reads to put those questions back in front of the operator — the
     * approval gate keeps its pending requests in memory, so after a restart the checkpoint is
     * the only place that still knows them.
     *
     * <p>Deliberately abstract: an implementation that cannot answer would silently turn every
     * restart into lost approvals, so it must say so by implementing it, not inherit an empty list.
     */
    List<RunCheckpoint> awaitingApproval();

    /** A process-local, in-memory reference implementation — not durable across a JVM restart. */
    static CheckpointStore inMemory() {
        return new InMemoryCheckpointStore();
    }

    /** Discards every write and always reads empty — zero overhead, the default. */
    static CheckpointStore noop() {
        return NoopCheckpointStore.INSTANCE;
    }
}
