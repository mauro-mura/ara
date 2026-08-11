package io.ara.core.llm;

/**
 * Policy that governs how {@link LlmRouter} selects an {@link LlmClient} for each call.
 */
public enum LlmSelectionPolicy {

    /** Always use the primary client; never attempt fallbacks. */
    PRIMARY_ONLY,

    /**
     * Try the primary client; on any exception (rate-limit, network error, 5xx)
     * retry the call with the next fallback in declaration order.
     * All failures are logged before switching.
     */
    FAILOVER,

    /** Distribute calls sequentially across all profiles (primary + fallbacks). */
    ROUND_ROBIN,

}
