package io.ara.core.llm;

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
        if (workingMemoryEviction != null) {
            String ev = workingMemoryEviction.toLowerCase().strip();
            if (!ev.equals("drop_oldest") && !ev.equals("drop_middle") && !ev.equals("summarize"))
                throw new IllegalArgumentException(
                        "workingMemoryEviction must be 'drop_oldest', 'drop_middle', or 'summarize'");
        }
        if (maxConversationTurns < 0)
            throw new IllegalArgumentException("maxConversationTurns must be >= 0");
        workingMemoryEviction = Objects.requireNonNullElse(workingMemoryEviction, "drop_middle");
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(0, "drop_middle", 0, 2, null);
    }
}
