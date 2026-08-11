package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.memory.MemoryEntry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Serialisable snapshot of all execution state needed to checkpoint and restore
 * an {@link AraAgent} mid-execution.
 *
 * <p>Captures execution state passed to {@link AgentInterceptor} hooks:
 * the full working memory window, the current iteration count and the
 * current agent lifecycle state.
 *
 * @param agentId        the agent this context belongs to
 * @param taskId         the task currently being processed
 * @param currentState   the agent's lifecycle state at the time of checkpointing
 * @param iterationCount number of ReAct iterations completed so far
 * @param workingMemory  the full conversation window at checkpoint time
 * @param totalTokens    cumulative tokens consumed across all LLM calls so far
 * @param savedAt        wall-clock time of this checkpoint
 */
public record AgentExecutionContext(
        AgentId agentId,
        String taskId,
        AgentState currentState,
        int iterationCount,
        List<MemoryEntry> workingMemory,
        int totalTokens,
        Instant savedAt
) {
    public AgentExecutionContext {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(currentState, "currentState must not be null");
        Objects.requireNonNull(savedAt, "savedAt must not be null");
        // Defensive copy
        workingMemory = List.copyOf(
                Objects.requireNonNullElse(workingMemory, List.of()));
    }
}
