package io.ara.runtime.memory;

import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.ToolCallMetadata;

import java.util.ArrayList;
import java.util.Collections;
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
    public List<MemoryEntry> workingMemory() {
        return Collections.unmodifiableList(working);
    }

    @Override
    public void clearWorkingMemory() {
        working.clear();
    }
}
