package io.ara.runtime.provider;

import io.ara.core.agent.AgentConfig;
import io.ara.core.provider.AgentProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@link AgentProvider} that returns a fixed set of {@link AgentConfig} objects
 * supplied at construction time.
 *
 * <pre>{@code
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient(llmClient)
 *     .agentProvider(ExplicitAgentProvider.of(
 *         AgentConfig.defaults().agentType("researcher").build(),
 *         AgentConfig.defaults().agentType("writer").build()))
 *     .build();
 * }</pre>
 */
public final class ExplicitAgentProvider implements AgentProvider {

    private final List<AgentConfig> agentConfigs;

    private ExplicitAgentProvider(List<AgentConfig> agentConfigs) {
        this.agentConfigs = List.copyOf(agentConfigs);
    }

    public static ExplicitAgentProvider of(AgentConfig... configs) {
        Objects.requireNonNull(configs, "configs must not be null");
        return new ExplicitAgentProvider(Arrays.asList(configs));
    }

    public static ExplicitAgentProvider of(List<AgentConfig> configs) {
        Objects.requireNonNull(configs, "configs must not be null");
        return new ExplicitAgentProvider(configs);
    }

    @Override
    public List<AgentConfig> configs() {
        return agentConfigs;
    }
}
