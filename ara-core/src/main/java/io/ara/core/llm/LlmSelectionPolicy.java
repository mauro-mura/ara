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
     *
     * <p>In {@code ara-runtime} every candidate carries a small passive circuit breaker:
     * after a few consecutive failures (network errors and 5xx only) a candidate is skipped
     * on subsequent calls without paying its connect/read timeout again, and is re-probed
     * with a single trial call after a short cooldown. Its health always comes from real
     * traffic — never from background probe calls — so a failing primary stops wasting the
     * per-request timeout while the pool keeps serving from the fallback until recovery.
     */
    FAILOVER,

    /** Distribute calls sequentially across all profiles (primary + fallbacks). */
    ROUND_ROBIN,

}
