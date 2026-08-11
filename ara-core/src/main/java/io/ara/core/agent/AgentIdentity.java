package io.ara.core.agent;

import io.ara.core.common.AgentId;

import java.util.List;
import java.util.Objects;

/**
 * Identity sub-record of {@link AgentConfig}: who the agent is.
 */
public record AgentIdentity(
        AgentId      agentId,
        String       agentType,
        String       name,
        String       description,
        String       version,
        List<String> tags,
        String       systemPrompt,
        String       promptCatalogId
) {
    public AgentIdentity {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(agentType, "agentType must not be null");
        if (agentType.isBlank()) throw new IllegalArgumentException("agentType must not be blank");
        systemPrompt    = Objects.requireNonNullElse(systemPrompt, "You are a helpful AI agent.");
        name            = Objects.requireNonNullElse(name, "");
        description     = Objects.requireNonNullElse(description, "");
        version         = Objects.requireNonNullElse(version, "1.0.0");
        tags            = List.copyOf(Objects.requireNonNullElse(tags, List.of()));
    }

    public static AgentIdentity defaults(String agentType) {
        return new AgentIdentity(AgentId.generate(), agentType,
                "", "", "1.0.0", List.of(),
                "You are a helpful AI agent.", null);
    }
}
