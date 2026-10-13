package io.ara.core.agent;

/**
 * The pair {@code AgentFactory} owns at the moment it builds a per-session memory manager
 * (ADR-0091 D3): the agent's configuration and the session the manager will serve.
 *
 * <p>{@code AgentFactory} used to receive only the {@link AgentConfig} and discard the
 * {@link SessionId} at the exact point it constructed the manager
 * ({@code Function<AgentConfig, MemoryManager>}); a memory implementation that scopes its
 * events per session had no way to learn it. This record is the additive alternative:
 * the builder accepts a factory over {@code MemoryScope}, and every agent keeps getting a
 * plain {@link MemoryManager} — the rest of ARA does not change.
 *
 * @param config  the agent's configuration; never {@code null}
 * @param session the session the manager will serve; never {@code null}
 */
public record MemoryScope(AgentConfig config, SessionId session) {

    public MemoryScope {
        java.util.Objects.requireNonNull(config, "config must not be null");
        java.util.Objects.requireNonNull(session, "session must not be null");
    }
}
