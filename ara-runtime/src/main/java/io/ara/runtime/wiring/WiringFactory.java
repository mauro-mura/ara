package io.ara.runtime.wiring;

import io.ara.core.agent.AgentConfig;

/**
 * Builds an {@link AgentWiring} snapshot from an {@link AgentConfig} (ADR-039 §6).
 *
 * <p><strong>Must be a pure function of {@code config}.</strong> Implementations must
 * derive every transport/tool id exclusively from the argument received, never by
 * re-reading a mutable "current config" elsewhere — that is what prevents a TOCTOU race
 * where a concurrent {@code reconfigure} changes the expected transport between reading
 * the config and pinning it (ADR-039 "Invariant di purezza della WiringFactory").
 */
@FunctionalInterface
public interface WiringFactory {

    /**
     * Builds a wiring for {@code config}, acquiring whatever leases its LLM profiles and
     * tools need. Transactional: if acquiring one lease fails after others already
     * succeeded, implementations must release everything already acquired before
     * propagating the failure — no orphaned leases, no partially-pinned wiring.
     */
    AgentWiring build(AgentConfig config);
}
