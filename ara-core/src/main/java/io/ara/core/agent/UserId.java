package io.ara.core.agent;

/**
 * Identifies the human or account behind one or more {@link SessionId}s (Agno {@code
 * user_id} parity — see ADR-043 rev. 3).
 *
 * <p>Where a {@link SessionId} scopes a single conversation thread, {@code UserId} scopes
 * whatever should survive across many of that user's threads — see {@code
 * RunContext#userMemory()}. A task with no {@code userId} simply has no cross-session
 * memory, same semantics as a task with no {@code sessionId} falling back to an ephemeral
 * one — there is no {@code ephemeral()} factory here because there is nothing sensible to
 * generate: an anonymous user has no identity to scope memory to.
 */
public record UserId(String value) {

    public UserId {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("UserId must not be blank");
    }

    /** User identified by an explicit, stable id. */
    public static UserId of(String id) {
        return new UserId(id);
    }
}
