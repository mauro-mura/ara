package io.ara.core.agent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Static convenience helpers built on top of the minimal {@link AraAgent} contract.
 *
 * <p>Kept separate from the interface so {@code AraAgent} stays a small set of
 * methods every implementation must reason about; these are derived operations
 * with no per-implementation state.
 */
public final class AraAgents {

    private AraAgents() {}

    /** Submits a plain-text prompt and returns the full {@link AgentResponse}. */
    public static AgentResponse ask(AraAgent agent, String prompt) {
        Objects.requireNonNull(agent, "agent must not be null");
        return agent.execute(AgentTask.of(prompt));
    }

    /**
     * Submits a plain-text prompt and returns only the answer text — the empty string when
     * the execution failed, since {@link AgentResponse#failure} sets {@code content} to
     * {@code ""}. No null check is needed: {@code AgentResponse} rejects a null
     * {@code content} in its compact constructor, so {@code content()} is never null.
     */
    public static String askText(AraAgent agent, String prompt) {
        return ask(agent, prompt).content();
    }

    /**
     * Asynchronous execution on virtual threads.
     *
     * @param executor typically {@code AraRuntime.executor()}
     */
    public static AgentFuture executeAsync(AraAgent agent, AgentTask task, Executor executor) {
        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(task, "task must not be null");
        return AgentFuture.async(() -> agent.execute(task), executor);
    }

    /**
     * Returns a machine-readable description of the agent's identity and capabilities,
     * derived from its current {@link AgentConfig}.
     */
    public static AgentCard agentCard(AraAgent agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        AgentConfig config = agent.config();
        AgentIdentity id = config.identity();
        AgentCapabilities caps = new AgentCapabilities(
                config.streamingEnabled(),
                config.maxConversationTurns() > 0,
                List.of(config.plannerStrategy()));
        return new AgentCard(id.agentId(), id.name(), id.description(), id.version(), caps);
    }
}
