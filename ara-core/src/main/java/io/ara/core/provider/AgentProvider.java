package io.ara.core.provider;

import io.ara.core.agent.AgentConfig;

import java.util.List;

/**
 * Supplies the list of {@link AgentConfig} objects to instantiate at runtime startup.
 *
 * <p>Functional interface — implementations can be lambdas or method references:
 * <pre>{@code
 * AgentProvider provider = () -> List.of(config1, config2);
 * }</pre>
 *
 * <p>The runtime calls {@link #configs()} exactly once during {@code AraRuntime.start()}.
 * Implementations should throw an unchecked exception if the source is unavailable.
 */
@FunctionalInterface
public interface AgentProvider {

    /** Returns the agent configurations to instantiate; never {@code null}. */
    List<AgentConfig> configs();
}
