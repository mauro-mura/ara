package io.ara.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * {@link RunState} that reads through to {@code base} when a key is absent from
 * {@code own}, but writes exclusively to {@code own} — {@code base} is never mutated.
 * See {@link RunState#overlay(RunState, RunState)}.
 */
final class OverlayRunState implements RunState {

    private final RunState base;
    private final RunState own;

    OverlayRunState(RunState base, RunState own) {
        this.base = Objects.requireNonNull(base, "base must not be null");
        this.own  = Objects.requireNonNull(own,  "own must not be null");
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Optional<T> ownValue = own.get(key, type);
        return ownValue.isPresent() ? ownValue : base.get(key, type);
    }

    @Override
    public void put(String key, Object value) {
        own.put(key, value);
    }

    @Override
    public <T> T merge(String key, T value, BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
        return own.merge(key, value, remappingFunction);
    }

    @Override
    public Map<String, Object> snapshot() {
        // base first, own on top — own wins on key conflicts, giving one coherent view.
        Map<String, Object> merged = new LinkedHashMap<>(base.snapshot());
        merged.putAll(own.snapshot());
        return Map.copyOf(merged);
    }
}
