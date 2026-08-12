package io.ara.core.agent;

import io.ara.core.common.AgentId;

/**
 * Unchecked exception thrown by {@link AgentFuture#get()} when the future
 * completes with an unrecoverable error (exception in the computation thread,
 * not an {@link AgentResponse#isSuccess()} == false).
 *
 * <p>An {@code AgentResponse.isSuccess() == false} is a valid result, not an
 * exception — it represents an expected failure (contract violation, LLM timeout, etc.).
 * {@code AgentExecutionException} covers only infrastructure failures that
 * prevent production of any response.
 */
public final class AgentExecutionException extends RuntimeException {

    private static final long serialVersionUID = 5448922555227435142L;
	private final AgentId agentId;

    public AgentExecutionException(AgentId agentId, String message, Throwable cause) {
        super(message, cause);
        this.agentId = agentId;
    }

    public AgentId agentId() { return agentId; }
}
