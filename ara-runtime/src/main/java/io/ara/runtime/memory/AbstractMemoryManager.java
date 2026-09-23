package io.ara.runtime.memory;

import io.ara.core.media.MediaRef;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.ToolCallMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class that provides the canonical in-process working-memory implementation.
 *
 * <p>Subclasses inherit {@link #appendToWorkingMemory}, {@link #workingMemory}, and
 * {@link #clearWorkingMemory} and only need to implement the semantic/episodic tiers.
 * {@link SlidingWindowMemoryManager} overrides {@code appendToWorkingMemory} to apply
 * token-budget eviction while still sharing the same {@code working} list.
 */
public abstract class AbstractMemoryManager implements MemoryManager {

    protected final List<MemoryEntry> working = new ArrayList<>();

    @Override
    public void appendToWorkingMemory(String role, String content) {
        working.add(MemoryEntry.of(role, content));
    }

    @Override
    public void appendToWorkingMemory(String role, String content, ToolCallMetadata metadata) {
        working.add(MemoryEntry.of(role, content, metadata));
    }

    @Override
    public void appendToWorkingMemory(String role, String content, List<MediaRef> media) {
        working.add(MemoryEntry.of(role, content, media));
    }

    /**
     * P6/U19, 2026-09-23: a snapshot, not a live view over {@link #working}. The previous
     * {@code Collections.unmodifiableList(working)} was read-only to its caller but still
     * backed by the same mutable {@code ArrayList} this class keeps mutating — a caller
     * iterating it (building a prompt, an {@code AgentExecutionContext} an interceptor reads
     * later) while {@code appendToWorkingMemory}/{@code clearWorkingMemory}/eviction touched
     * {@code working} underneath it risked a {@link java.util.ConcurrentModificationException}
     * or, worse, a torn read that never throws. {@link List#copyOf} is the one-time cost this
     * trades for it — no longer O(1) identity-memoised, but every call site in the codebase
     * (verified: {@code ReactExecutionSupport}, {@code ReflexionStrategy}, {@code
     * PlanExecuteStrategy}, {@code AgentInstance.context}) only ever reads the result once,
     * immediately, never holds it across a later mutation — none relied on the old live view.
     */
    @Override
    public List<MemoryEntry> workingMemory() {
        return List.copyOf(working);
    }

    @Override
    public void clearWorkingMemory() {
        working.clear();
    }
}
