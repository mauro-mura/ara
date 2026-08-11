package io.ara.runtime.memory;

/**
 * Strategy for evicting entries from working memory when the token budget is exceeded.
 *
 * <p>Configured via {@link io.ara.core.agent.AgentConfig#workingMemoryEviction()}
 * (string values: {@code "drop_oldest"}, {@code "drop_middle"}, {@code "summarize"}).
 * {@link SlidingWindowMemoryManager} applies this policy on every
 * {@link io.ara.core.memory.MemoryManager#appendToWorkingMemory} call.
 */
public enum EvictionPolicy {

    /** Remove the oldest messages first (FIFO). Loses historical context fastest. */
    DROP_OLDEST,

    /**
     * Remove messages from the middle of the window, preserving the system/user opening
     * and the most recent exchanges. Best general-purpose choice for most LLM tasks.
     */
    DROP_MIDDLE,

    /**
     * Summarise the oldest messages into a compact representation before evicting.
     * Requires a configured summariser agent; if the summariser fails, silently
     * degrades to {@link #DROP_MIDDLE}.
     */
    SUMMARIZE;

    /** Parses the string value from {@code AgentConfig.workingMemoryEviction()}. */
    public static EvictionPolicy from(String value) {
        if (value == null || value.isBlank()) return DROP_MIDDLE;
        return switch (value.toLowerCase().strip()) {
            case "drop_oldest" -> DROP_OLDEST;
            case "drop_middle" -> DROP_MIDDLE;
            case "summarize"   -> SUMMARIZE;
            default -> throw new IllegalArgumentException(
                    "Unknown eviction policy '" + value + "'. Valid values: drop_oldest, drop_middle, summarize");
        };
    }
}
