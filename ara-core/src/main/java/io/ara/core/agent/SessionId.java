package io.ara.core.agent;

/**
 * Identifies a session of an agent execution.
 *
 * <p>A {@code SessionId} isolates execution context (working memory,
 * state machine state) between different clients using the same agent.
 * Sessions with the same ID share working memory across successive calls —
 * useful for multi-turn conversations and FSM pipelines with loops.
 *
 * <p>Generating a UUID per request produces stateless isolated executions.
 * Passing the same ID in successive calls produces memory persistent between turns.
 */
public record SessionId(String value) {

    public SessionId {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("SessionId must not be blank");
    }

    /** Generates a unique ephemeral session — stateless, no shared memory. */
    public static SessionId ephemeral() {
        return new SessionId(java.util.UUID.randomUUID().toString());
    }

    /** Session with an explicit name — persistent across calls with the same ID. */
    public static SessionId of(String id) {
        return new SessionId(id);
    }
}
