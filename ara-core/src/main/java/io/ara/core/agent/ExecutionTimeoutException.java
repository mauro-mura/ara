package io.ara.core.agent;

import java.time.Duration;

/**
 * Thrown when an agent execution exceeds its configured {@link AgentConfig#executionTimeout()}.
 */
public class ExecutionTimeoutException extends RuntimeException {

    private static final long serialVersionUID = -1662367909337347451L;
    
	private final Duration timeout;

    public ExecutionTimeoutException(Duration timeout) {
        super("Execution exceeded timeout of " + timeout.toSeconds() + "s");
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }
}
