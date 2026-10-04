package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;

import java.util.Optional;

/**
 * The minimal slice of the variant archive that the recipe-cache fast-path needs
 * (ADR-0072 D3/D4). The full archive — quality-diversity selection, lineage, staleness —
 * is ADR-0082's job; this port exposes only what {@code RecipeCacheResolver} asks of it:
 * "for this task class, is there a promoted variant, and what is its behavioural config?"
 *
 * <p><b>Only promoted (Default) variants.</b> {@link #bestFor} must never return a
 * {@code draft}/{@code shadow}/{@code canary} or a {@code stale} variant (ADR-0072 D4,
 * ADR-0062): a fast-path that resolved an unvalidated variant would use production traffic
 * as its test bed, defeating the promotion gate (ADR-0083). The filtering lives in the
 * implementation, not in the caller.
 *
 * <p><b>Why the behavioural payload, not the whole spec.</b> The port predates
 * {@link AgentSpec}'s move here (ADR-0134) and outlives the reason it originally stated:
 * the fast-path needs only the payload it builds the worker from. An archived entry
 * (ADR-0082 D2) carries {@code specHash}, not the full spec, so an implementation backed
 * by the real archive resolves configs directly; resolving {@code specHash → AgentSpec}
 * is the caller's job, done where the spec is actually kept. A port on {@code AgentSpec}
 * would force every implementation into that second lookup it does not need.
 */
public interface SpecArchive {

    /**
     * The behavioural config of the best promoted variant for {@code label} (a
     * {@code task_class}), or empty if there is no promoted variant — the cache miss that
     * falls to the factory's {@code else} arc (ADR-0072 D2).
     */
    Optional<AgentConfig> bestFor(String label);

    /** A process-local archive that holds only what is explicitly {@code put} into it. */
    static SpecArchive inMemory() {
        return new InMemorySpecArchive();
    }
}
