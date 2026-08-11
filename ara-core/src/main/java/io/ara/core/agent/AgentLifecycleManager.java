package io.ara.core.agent;

/**
 * Minimal interface for creating and permanently destroying {@link AraAgent} instances.
 *
 * <p>Extracting these two operations into an interface allows callers to depend on
 * this abstraction rather than on the concrete {@code AgentFactory}.
 *
 * <h3>Semantic distinction: destroy vs terminate</h3>
 * <ul>
 *   <li>{@link #destroyPermanently} — a <em>permanent</em> user action: terminates the agent,
 *       cleans up its checkpoint, and deregisters it.</li>
 *   <li>{@code AraAgent#terminate()} — a <em>runtime</em> signal used internally
 *       during platform shutdown; it stops execution but preserves state for restart recovery.</li>
 * </ul>
 *
 * <p>Known implementations:
 * <ul>
 *   <li>{@code AgentFactory} — pure in-memory, no persistence.</li>
 * </ul>
 */
public interface AgentLifecycleManager {

    /**
     * Creates a new {@link AraAgent} from the given config, wires its runtime
     * collaborators, and registers it in the agent registry.
     *
     * @param config the agent definition; must not be {@code null}
     * @return the live, ready-to-use agent instance; never {@code null}
     */
    AraAgent create(AgentConfig config);

    /**
     * Creates a new {@link AraAgent} wired with the given {@link AgentContract} and
     * registers it. Implementations that do not support contracts fall back to
     * {@link #create(AgentConfig)}.
     *
     * @param config   the agent definition; must not be {@code null}
     * @param contract the I/O contract; {@code null} is treated as no contract
     * @return the live, ready-to-use agent instance; never {@code null}
     */
    default AraAgent create(AgentConfig config, AgentContract contract) {
        return create(config);
    }

    /**
     * Permanently destroys the given agent: terminates its execution, cleans up
     * its checkpoint, and deregisters it. Persistent implementations also remove
     * the agent's config from the repository.
     *
     * <p>For a reversible shutdown that preserves checkpoints and registry state,
     * use {@link AraAgent#terminate()} instead.
     *
     * @param agent the agent to destroy; must not be {@code null}
     */
    void destroyPermanently(AraAgent agent);
}