package io.ara.runtime.wiring;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmClient;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of everything a session needs to execute, pinned for the session's
 * whole lifetime (ADR-039 §1, §6 "Forma di design").
 *
 * <p>Aggregates the config (parameter/governance axes) and the already-resolved
 * collaborators the strategy calls — not raw leases. The {@link #llm()} policy
 * (single / failover / round-robin) is resolved once at build time by {@code
 * WiringFactory}, over transports already leased from the shared registries; {@link
 * #leases()} is what {@link #close()} releases at session teardown.
 */
public record AgentWiring(
        AgentConfig config,
        LlmClient llm,
        ToolRegistry toolRegistry,
        List<Lease<?>> leases
) implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWiring.class);

    public AgentWiring {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(llm, "llm must not be null");
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        leases = List.copyOf(Objects.requireNonNull(leases, "leases must not be null"));
    }

    /**
     * Releases every leased resource. Idempotent (each {@link Lease#close()} is) and
     * best-effort: a failure releasing one lease does not prevent the rest from closing.
     */
    @Override
    public void close() {
        for (Lease<?> lease : leases) {
            try {
                lease.close();
            } catch (RuntimeException e) {
                log.warn("Error releasing a lease while closing AgentWiring", e);
            }
        }
    }
}
