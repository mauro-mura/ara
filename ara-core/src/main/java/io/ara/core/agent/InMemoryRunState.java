package io.ara.core.agent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/** Default {@link RunState}: a plain concurrent map, no persistence beyond the JVM's lifetime. */
final class InMemoryRunState implements RunState {

    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object v = data.get(key);
        return (v != null && type.isInstance(v)) ? Optional.of(type.cast(v)) : Optional.empty();
    }

    @Override
    public void put(String key, Object value) {
        data.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked") // ConcurrentHashMap<String,Object> erases T; callers own the type contract for `key`.
    public <T> T merge(String key, T value, BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
        return (T) data.merge(key, value, (BiFunction<Object, Object, Object>) remappingFunction);
    }

    @Override
    public Map<String, Object> snapshot() {
        return Map.copyOf(data);
    }
}
