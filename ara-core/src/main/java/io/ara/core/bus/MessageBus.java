package io.ara.core.bus;

import java.time.Duration;

/**
 * Intra-node message bus for agent-to-agent communication.
 *
 * <p>The bus routes {@link AgentMessage} instances to agents registered on the
 * same platform node. Remote routing (cross-node) is outside this contract and
 * belongs to a future cluster-aware implementation.
 *
 * <p>Two delivery modes are supported:
 * <dl>
 *   <dt>{@link #send} — fire-and-forget</dt>
 *   <dd>Delivers the message asynchronously on a virtual thread. The reply is
 *       discarded. Use for notifications or best-effort delegation where the
 *       caller does not need the result.</dd>
 *
 *   <dt>{@link #request} — synchronous request/reply</dt>
 *   <dd>Delivers the message and blocks until the recipient returns a response
 *       or the {@code timeout} expires. The recipient's {@link io.ara.core.agent.AgentResponse}
 *       is wrapped in a reply {@link AgentMessage} that preserves the original
 *       {@code correlationId}. Use inside a ReAct tool call when the result is
 *       needed to continue reasoning.</dd>
 * </dl>
 *
 * <h3>Error semantics</h3>
 * <ul>
 *   <li>If the recipient is not registered, both methods throw
 *       {@link java.util.NoSuchElementException}.</li>
 *   <li>If the recipient is not in {@code IDLE} state (busy), both methods throw
 *       {@link IllegalStateException}.</li>
 *   <li>If {@link #request} times out, it throws {@link java.util.concurrent.TimeoutException}
 *       wrapped in a {@link RuntimeException}.</li>
 * </ul>
 */
public interface MessageBus {

    /**
     * Sends a message to the recipient agent asynchronously (fire-and-forget).
     *
     * <p>The recipient's response, if any, is silently discarded.
     * Delivery failures (agent not found, agent busy) are logged at WARN level
     * but do not propagate to the caller.
     *
     * @param message the message to deliver; must not be {@code null}
     */
    void send(AgentMessage message);

    /**
     * Sends a message to the recipient agent and blocks until a reply is received
     * or the timeout elapses.
     *
     * <p>The message's {@code correlationId} is propagated to the
     * {@link io.ara.core.agent.AgentTask} submitted to the recipient, making
     * the full delegation chain observable in logs.
     *
     * @param message the message to deliver; must not be {@code null}
     * @param timeout maximum time to wait for a reply; must be positive
     * @return the reply message produced by the recipient agent
     * @throws RuntimeException wrapping {@link java.util.concurrent.TimeoutException}
     *                          if no reply arrives within {@code timeout}
     * @throws IllegalStateException if the recipient agent is not in {@code IDLE} state
     * @throws java.util.NoSuchElementException if the recipient is not registered
     */
    AgentMessage request(AgentMessage message, Duration timeout);
}