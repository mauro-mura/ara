package io.ara.core.bus;

import io.ara.core.agent.AgentResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A delegated task that has been started and not necessarily finished: what
 * {@link MessageBus#delegate} hands back instead of waiting.
 *
 * <p>Carries the three things a caller that does not wait still needs: the task's id (the one
 * the delegate runs under, so it matches the delegate's own trace and events), the future of
 * the delegate's <em>whole</em> {@link AgentResponse} (not just its text, so a failure or a
 * suspension for approval keeps its reason), and the way to stop it.
 *
 * <p>Immutable. {@code cancel} is safe to run from any thread and more than once.
 *
 * @param taskId   the id of the {@link io.ara.core.agent.AgentTask} the recipient runs
 * @param response completes with the recipient's response; never completes with an
 *                 exception for a recipient that merely failed (that is a failed response)
 * @param cancel   asks the recipient to stop: cooperative, like terminating a session
 */
public record Delegation(String taskId, CompletableFuture<AgentResponse> response, Runnable cancel) {

    public Delegation {
        Objects.requireNonNull(taskId,   "taskId must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(cancel,   "cancel must not be null");
    }
}
