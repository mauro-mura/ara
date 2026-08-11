package io.ara.core.agent;

import io.ara.core.common.AgentId;

import java.util.Objects;

/**
 * Machine-readable description of an agent's identity and capabilities.
 *
 * <p>Generated on demand via {@link AraAgents#agentCard(AraAgent)} — not a method on
 * {@code AraAgent} itself, which stays a minimal contract (see {@link AraAgents}). Used by
 * {@code AgentRegistry} to build the discovery context injected into Supervisor agent
 * prompts, and in future by ara-cluster for cross-node discovery.
 */
public record AgentCard(
        AgentId           agentId,
        String            name,
        String            description,
        String            version,
        AgentCapabilities capabilities
) {
    public AgentCard {
        Objects.requireNonNull(agentId, "agentId must not be null");
        name        = Objects.requireNonNullElse(name, "");
        description = Objects.requireNonNullElse(description, "");
        version     = Objects.requireNonNullElse(version, "1.0.0");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
    }
}
