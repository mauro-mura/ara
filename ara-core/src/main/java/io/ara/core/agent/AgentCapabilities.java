package io.ara.core.agent;

import java.util.List;
import java.util.Objects;

/**
 * Capability summary included in an {@link AgentCard} for agent discovery.
 */
public record AgentCapabilities(
        boolean      streamingEnabled,
        boolean      conversationalMemory,
        List<String> supportedStrategies
) {
    public AgentCapabilities {
        supportedStrategies = List.copyOf(Objects.requireNonNullElse(supportedStrategies, List.of()));
    }
}
