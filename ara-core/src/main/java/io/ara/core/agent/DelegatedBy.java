package io.ara.core.agent;

import java.util.Objects;

/**
 * The run that delegated a task: the delegating agent and the task it was executing. Travels
 * in a delegated task's {@link RunContext} under {@link RunContext#DELEGATED_BY_KEY}.
 *
 * <p>Immutable; thread-safe.
 *
 * @param agentId id of the agent that delegated
 * @param taskId  id of the task that agent was executing when it delegated
 */
public record DelegatedBy(String agentId, String taskId) {

    public DelegatedBy {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
    }
}
