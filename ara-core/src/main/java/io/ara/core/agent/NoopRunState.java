package io.ara.core.agent;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * No-op {@link RunState}: discards every write, never returns a value. The default for
 * a {@link RunContext} that hasn't been bound to a session yet — {@code AgentInstance}
 * uses reference identity against {@link #INSTANCE} to detect "not yet bound".
 */
final class NoopRunState implements RunState {

    static final NoopRunState INSTANCE = new NoopRunState();

    private NoopRunState() {}

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.empty();
    }

    @Override
    public void put(String key, Object value) {
        // discarded
    }

    @Override
    public <T> T merge(String key, T value, BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
        return value;
    }

    @Override
    public Map<String, Object> snapshot() {
        return Map.of();
    }
}
