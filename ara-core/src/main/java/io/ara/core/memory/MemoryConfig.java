package io.ara.core.memory;

import java.util.Objects;

/**
 * Memory sub-record of {@link io.ara.core.agent.AgentConfig}: working memory and
 * conversation settings.
 */
public record MemoryConfig(
        int    workingMemoryTokenBudget,
        String workingMemoryEviction,
        int    maxConversationTurns,
        int    maxReflections,
        String reflectionPrompt
) {
    public MemoryConfig {
        if (workingMemoryTokenBudget < 0)
            throw new IllegalArgumentException("workingMemoryTokenBudget must be >= 0");
        if (maxConversationTurns < 0)
            throw new IllegalArgumentException("maxConversationTurns must be >= 0");
        // The eviction-strategy vocabulary ("drop_oldest", "drop_middle", "summarize") is
        // owned by io.ara.runtime.memory.EvictionPolicy, which ara-core cannot depend on —
        // validating the same closed set here too would mean keeping two independently
        // -maintained copies of it in sync across modules. EvictionPolicy.from(...) is the
        // single place that rejects an unknown value; only "unspecified" is handled here.
        workingMemoryEviction = Objects.requireNonNullElse(workingMemoryEviction, "drop_middle");
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(0, "drop_middle", 0, 2, null);
    }
}
