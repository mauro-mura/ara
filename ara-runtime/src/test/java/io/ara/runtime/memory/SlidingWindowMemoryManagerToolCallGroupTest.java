package io.ara.runtime.memory;

import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.ToolCallMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SlidingWindowMemoryManager} never evicts half of a tool-call/tool-result
 * exchange. Splitting one leaves an orphaned {@code "tool"} entry with no preceding {@code
 * "assistant_tool_call"}/{@code "assistant_tool_calls"} header — harmless before native
 * reconstruction existed (it just degraded to a generic user turn), but once {@code
 * ToolConversionUtils.toNativeAwareChatMessage} reconstructs it as a real {@code
 * ToolExecutionResultMessage}, a real provider (OpenAI) rejects an orphaned one with a 400.
 */
class SlidingWindowMemoryManagerToolCallGroupTest {

    private static void assertNoOrphanedToolResults(List<MemoryEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (!"tool".equals(entries.get(i).role())) continue;
            assertTrue(i > 0, "orphaned tool result at position 0 — no preceding tool-call header");
            String prevRole = entries.get(i - 1).role();
            assertTrue("tool".equals(prevRole)
                            || "assistant_tool_call".equals(prevRole)
                            || "assistant_tool_calls".equals(prevRole),
                    "tool result at position " + i + " has no preceding tool-call header (prev role=" + prevRole + ")");
        }
    }

    @Test
    void dropOldest_evictsWholeToolCallGroupTogether_neverPartially() {
        SlidingWindowMemoryManager mem = new SlidingWindowMemoryManager(30, EvictionPolicy.DROP_OLDEST);

        mem.appendToWorkingMemory("assistant_tool_calls", "H".repeat(40));
        assertNoOrphanedToolResults(mem.workingMemory());
        mem.appendToWorkingMemory("tool", "R".repeat(40), new ToolCallMetadata("call-1", "search"));
        assertNoOrphanedToolResults(mem.workingMemory());
        mem.appendToWorkingMemory("tool", "R".repeat(40), new ToolCallMetadata("call-2", "read"));
        assertNoOrphanedToolResults(mem.workingMemory());
        mem.appendToWorkingMemory("user", "next question");
        assertNoOrphanedToolResults(mem.workingMemory());

        // Budget is small enough that the whole 3-entry group at the front must go — and it
        // must go together, not leaving e.g. the header evicted but a "tool" result behind.
        List<MemoryEntry> remaining = mem.workingMemory();
        boolean anyGroupEntrySurvived = remaining.stream()
                .anyMatch(e -> "assistant_tool_calls".equals(e.role()) || "tool".equals(e.role()));
        assertFalse(anyGroupEntrySurvived, "the whole tool-call group should have been evicted together");
        assertEquals("next question", remaining.get(remaining.size() - 1).content());
    }

    @Test
    void dropMiddle_evictsWholeToolCallGroupTogether_neverPartially() {
        SlidingWindowMemoryManager mem = new SlidingWindowMemoryManager(40, EvictionPolicy.DROP_MIDDLE);

        mem.appendToWorkingMemory("system", "sys");
        mem.appendToWorkingMemory("user", "hi");
        mem.appendToWorkingMemory("assistant_tool_calls", "H".repeat(40));
        mem.appendToWorkingMemory("tool", "R".repeat(40), new ToolCallMetadata("call-1", "search"));
        mem.appendToWorkingMemory("tool", "R".repeat(40), new ToolCallMetadata("call-2", "read"));
        assertNoOrphanedToolResults(mem.workingMemory());

        // Force further eviction cycles by appending more content
        for (int i = 0; i < 5; i++) {
            mem.appendToWorkingMemory("assistant", "more content " + i + " ".repeat(10));
            assertNoOrphanedToolResults(mem.workingMemory());
        }
    }

    @Test
    void plainMessages_stillEvictOneAtATime_unaffectedByGrouping() {
        SlidingWindowMemoryManager mem = new SlidingWindowMemoryManager(20, EvictionPolicy.DROP_OLDEST);

        mem.appendToWorkingMemory("system", "S".repeat(40));
        mem.appendToWorkingMemory("user", "U".repeat(40));
        mem.appendToWorkingMemory("assistant", "A".repeat(40));

        // No tool-call roles involved — single-entry eviction, exactly as before this fix.
        List<MemoryEntry> remaining = mem.workingMemory();
        assertEquals(1, remaining.size());
        assertEquals("A".repeat(40), remaining.get(0).content());
    }
}
