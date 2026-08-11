package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.ExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the appropriate {@link ExecutionStrategy} for a given {@link AgentConfig}.
 *
 * <p>The planner holds an immutable registry of named strategies built at startup.
 * Selection is O(1) via a hash map lookup on {@link AgentConfig#plannerStrategy()}.
 * If the requested strategy is not registered, the planner falls back to the
 * {@value #DEFAULT_STRATEGY} strategy and logs a warning. If even the default is
 * absent, an {@link IllegalStateException} is thrown at selection time.
 *
 * <p>New strategies are registered at startup through the {@link Builder}. There is
 * no runtime re-registration — hot-swapping strategies is a Meta-Agent concern that
 * creates a new planner and re-wires the factory.
 */
public final class ExecutionPlanner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPlanner.class);

    static final String DEFAULT_STRATEGY = "react";

    private final Map<String, ExecutionStrategy> strategies;

    private ExecutionPlanner(Map<String, ExecutionStrategy> strategies) {
        this.strategies = Map.copyOf(strategies);
    }

    /**
     * Selects the strategy indicated by {@code config.plannerStrategy()}.
     *
     * @param config the agent configuration containing the strategy name
     * @return the matching {@link ExecutionStrategy}; never {@code null}
     * @throws IllegalStateException if neither the requested strategy nor the
     *                               default fallback is registered
     */
    public ExecutionStrategy select(AgentConfig config) {
        String name = Objects.requireNonNull(config, "config must not be null").plannerStrategy();
        ExecutionStrategy strategy = strategies.get(name);

        if (strategy == null) {
            log.warn("Strategy [{}] not registered; falling back to default [{}]", name, DEFAULT_STRATEGY);
            strategy = strategies.get(DEFAULT_STRATEGY);
        }
        if (strategy == null) {
            throw new IllegalStateException(
                    "No strategy [%s] registered and default [%s] is also absent. Registered: %s"
                            .formatted(name, DEFAULT_STRATEGY, strategies.keySet()));
        }
        return strategy;
    }

    /**
     * Returns {@code true} if a strategy with the given name is registered.
     *
     * @param name the strategy name to look up
     * @return {@code true} if found
     */
    public boolean hasStrategy(String name) {
        return strategies.containsKey(name);
    }

    /**
     * Returns a new {@link Builder} for constructing an {@code ExecutionPlanner}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link ExecutionPlanner}.
     *
     * <p>At least one strategy must be registered before {@link #build()} is called.
     * The first registered strategy is NOT automatically the default; the default
     * fallback is always {@value ExecutionPlanner#DEFAULT_STRATEGY}.
     */
    public static final class Builder {

        private final Map<String, ExecutionStrategy> strategies = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers a strategy under its {@link ExecutionStrategy#strategyName()}.
         *
         * @param strategy the strategy to register; must not be {@code null}
         * @return this builder
         */
        public Builder register(ExecutionStrategy strategy) {
            Objects.requireNonNull(strategy, "strategy must not be null");
            strategies.put(strategy.strategyName(), strategy);
            return this;
        }

        /**
         * Builds the {@link ExecutionPlanner}.
         *
         * @return the immutable planner
         * @throws IllegalStateException if no strategies have been registered
         */
        public ExecutionPlanner build() {
            if (strategies.isEmpty()) {
                throw new IllegalStateException(
                        "At least one ExecutionStrategy must be registered before building ExecutionPlanner");
            }
            return new ExecutionPlanner(strategies);
        }
    }
}