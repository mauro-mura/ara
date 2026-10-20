package io.ara.core.agent;

import java.util.List;
import java.util.Optional;

/** No-op {@link CheckpointStore}: discards every write, always reads empty. The default. */
final class NoopCheckpointStore implements CheckpointStore {

    static final NoopCheckpointStore INSTANCE = new NoopCheckpointStore();

    private NoopCheckpointStore() {}

    @Override
    public void save(RunCheckpoint checkpoint) {
        // discarded
    }

    @Override
    public Optional<RunCheckpoint> loadLatest(SessionId sessionId, String taskId) {
        return Optional.empty();
    }

    @Override
    public void delete(SessionId sessionId, String taskId) {
        // nothing to delete
    }

    @Override
    public List<RunCheckpoint> awaitingApproval() {
        return List.of();
    }
}
