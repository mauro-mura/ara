package io.ara.core.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local {@link CheckpointStore}: one concurrent map, no external I/O. */
final class InMemoryCheckpointStore implements CheckpointStore {

    // The key pairs the two identifiers a run is known by; a record gives it equals/hashCode for free.
    private record RunKey(SessionId sessionId, String taskId) {}

    private final ConcurrentHashMap<RunKey, RunCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public void save(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        checkpoints.put(new RunKey(checkpoint.sessionId(), checkpoint.taskId()), checkpoint);
    }

    @Override
    public Optional<RunCheckpoint> loadLatest(SessionId sessionId, String taskId) {
        return Optional.ofNullable(checkpoints.get(new RunKey(sessionId, taskId)));
    }

    @Override
    public void delete(SessionId sessionId, String taskId) {
        checkpoints.remove(new RunKey(sessionId, taskId));
    }

    @Override
    public List<RunCheckpoint> awaitingApproval() {
        return checkpoints.values().stream().filter(checkpoint -> checkpoint.pendingApproval() != null).toList();
    }
}
