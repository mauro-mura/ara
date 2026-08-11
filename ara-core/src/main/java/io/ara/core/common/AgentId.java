package io.ara.core.common;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed identifier for an {@link io.ara.core.agent.AraAgent}.
 *
 * <p>Using a dedicated type instead of a raw {@code String} or {@code UUID}
 * prevents accidental mix-ups between different kinds of identifiers
 * (agent, tool, session…) at compile time, with zero runtime overhead.
 */
public record AgentId(String value) {
    public AgentId {
        Objects.requireNonNull(value, "AgentId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AgentId value must not be blank");
        }
    }

    public static AgentId of(String value) {
        return new AgentId(value);
    }

    public static AgentId generate() {
        return new AgentId(UUID.randomUUID().toString());
    }
}
