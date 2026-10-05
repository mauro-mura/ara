package io.ara.runtime.memory;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticStore;
import io.ara.core.memory.ToolCallMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0078 D2–D4: real {@code SUMMARIZE} eviction with an honest fallback, episodic offload
 * before discard, and selective recall — all inert when their dependencies are absent.
 */
class SlidingWindowMemoryManagerContextTest {

    /** Fills the window past a small token budget with distinct user turns. */
    private static void fill(SlidingWindowMemoryManager m, int n) {
        for (int i = 0; i < n; i++) {
            m.appendToWorkingMemory("user", "message number " + i + " with some padding text to burn tokens");
        }
    }

    private static AraAgent summarizer(String output, boolean succeed, boolean throwing) {
        AgentId id = AgentId.generate();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                if (throwing) throw new IllegalStateException("summariser boom");
                return succeed
                        ? AgentResponse.success(task.taskId(), id, output, 1, 0, 0, Duration.ofMillis(1), List.of())
                        : AgentResponse.failure(task.taskId(), id, "no", Duration.ofMillis(1));
            }
            @Override public void terminate() {}
        };
    }

    static final class RecordingStore implements SemanticStore {
        final List<String> upserted = new ArrayList<>();
        List<MemoryEntry> nextSearchResult = List.of();

        @Override public void upsert(String agentId, String role, String type, String content, List<Float> vector) {
            upserted.add(content);
        }
        @Override public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
            return nextSearchResult;
        }
    }

    private static final EmbeddingClient EMBED = new EmbeddingClient() {
        @Override public List<Float> embed(String text) { return List.of(0.1f, 0.2f, 0.3f); }
        @Override public int dimensions() { return 3; }
    };

    @Test
    void summarizeBoundaryNeverSplitsAToolCallGroup() {
        // Two complete tool-call groups, then one big append that crosses the budget in a
        // single pass. The old boundary check inspected the last EVICTED entry instead of the
        // first KEPT one, so a header whose result sat just inside the anchor tail was evicted
        // while its result survived — an orphaned "tool" message that providers reject with 400.
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                seedBudget(), EvictionPolicy.SUMMARIZE, summarizer("SUMMARY", true, false));
        m.appendToWorkingMemory("system", "system pad");
        m.appendToWorkingMemory("user", "user pad");
        m.appendToWorkingMemory("assistant_tool_calls", "{\"calls\":[1]}");
        m.appendToWorkingMemory("tool", "r1", new ToolCallMetadata("c1", "t"));
        m.appendToWorkingMemory("assistant_tool_calls", "{\"calls\":[2]}");
        m.appendToWorkingMemory("tool", "r2", new ToolCallMetadata("c2", "t"));
        m.appendToWorkingMemory("assistant", "assistant pad");
        assertEquals(0, orphans(m.workingMemory()), "seeded window is well formed");

        m.appendToWorkingMemory("user", "BIG ".repeat(75));
        assertEquals(0, orphans(m.workingMemory()),
                "eviction boundary must keep each tool result with its header");
    }

    /**
     * Token budget that crosses only when the BIG append lands, over by exactly 1 — so exactly
     * one eviction pass runs. A tighter budget would let the while loop run a second pass that
     * evicts the orphaned result too, masking the boundary bug this test exists to catch.
     */
    private static int seedBudget() {
        SlidingWindowMemoryManager probe = new SlidingWindowMemoryManager(
                Integer.MAX_VALUE / 4, EvictionPolicy.SUMMARIZE, summarizer("SUMMARY", true, false));
        probe.appendToWorkingMemory("system", "system pad");
        probe.appendToWorkingMemory("user", "user pad");
        probe.appendToWorkingMemory("assistant_tool_calls", "{\"calls\":[1]}");
        probe.appendToWorkingMemory("tool", "r1", new ToolCallMetadata("c1", "t"));
        probe.appendToWorkingMemory("assistant_tool_calls", "{\"calls\":[2]}");
        probe.appendToWorkingMemory("tool", "r2", new ToolCallMetadata("c2", "t"));
        probe.appendToWorkingMemory("assistant", "assistant pad");
        int beforeBig = probe.estimatedTokens();
        probe.appendToWorkingMemory("user", "BIG ".repeat(75));
        return probe.estimatedTokens() - 1;
    }

    /** Number of tool results whose immediately preceding entry is not header or another tool. */
    private static int orphans(List<MemoryEntry> window) {
        int n = 0;
        for (int i = 0; i < window.size(); i++) {
            if (!"tool".equals(window.get(i).role())) continue;
            if (i == 0) { n++; continue; }
            String prev = window.get(i - 1).role();
            if (!prev.equals("tool") && !isToolHeader(prev)) n++;
        }
        return n;
    }

    private static boolean isToolHeader(String role) {
        return role.equals("assistant_tool_call") || role.equals("assistant_tool_calls");
    }

    @Test
    void toolResultAppendNeverStrandsALaterResultOfTheSameGroup() {
        // recordObservation appends each parallel tool result separately, and every append used
        // to run eviction. With a budget the header + first result already exceed, eviction
        // removed the whole group mid-assembly and the second result landed with no header.
        // Eviction is now deferred on tool-result appends until the group is closed.
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(2, EvictionPolicy.DROP_OLDEST);
        m.appendToWorkingMemory("assistant_tool_calls", "{\"calls\":[1,2]}");
        m.appendToWorkingMemory("tool", "r1", new ToolCallMetadata("c1", "t"));
        m.appendToWorkingMemory("tool", "r2", new ToolCallMetadata("c1", "t"));
        List<MemoryEntry> w = m.workingMemory();
        assertEquals(0, orphans(w),
                "results of one parallel call must never be stranded headerless: " + roles(w));
        assertTrue(w.stream().anyMatch(e -> isToolHeader(e.role())),
                "mid-group eviction was deferred, the whole group is still intact");

        // The next non-tool append closes the group — deferred eviction applies there.
        m.appendToWorkingMemory("assistant", "assistant pad");
        w = m.workingMemory();
        assertEquals(0, orphans(w), "eviction after the group closes still never strands a result");
        assertTrue(w.size() < 4, "deferred eviction did run: " + roles(w));
    }

    private static List<String> roles(List<MemoryEntry> w) {
        return w.stream().map(MemoryEntry::role).toList();
    }

    @Test
    void summarizeWithAnAgentOnAWindowTooSmallToCollapseDegradesInsteadOfThrowing() {
        // evictSummarize() measured its middle with toolCallGroupBounds(ANCHOR_COUNT), which
        // reads working.get(ANCHOR_COUNT) — index 2 of a 2-entry list. Any budget below what the
        // freshly seeded [system, user] window already costs therefore threw
        // IndexOutOfBoundsException out of appendToWorkingMemory, on the very first append.
        // The two budgets below span both realistic triggers: a plain text seed, and a single
        // attachment whose flat constant (PDF 6,000 / image 1,500) exceeds the budget outright.
        int[] budgets = {5, 50, 1000, 4000};
        for (int budget : budgets) {
            SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                    budget, EvictionPolicy.SUMMARIZE, summarizer("SUMMARY", true, false));
            assertDoesNotThrow(() -> {
                m.appendToWorkingMemory("system", "You are a contract analyst.");
                m.appendToWorkingMemory("user", "Review this contract.",
                        List.of(new io.ara.core.media.MediaRef(
                                "digest", "application/pdf", "contract.pdf", 10, null)));
            }, "budget=" + budget + " must degrade to DROP_MIDDLE, never throw");
            assertTrue(m.workingMemory().size() >= 1, "budget=" + budget + " kept at least one entry");
        }
    }

    @Test
    void summarizeWithoutAnAgentStillDegradesToDropMiddle() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(60, EvictionPolicy.SUMMARIZE);
        fill(m, 12);

        assertTrue(m.workingMemory().size() < 12, "eviction happened");
        assertFalse(m.workingMemory().stream().anyMatch(e -> hasLabel(e, "context_summary")),
                "no summary entry without a summariser");
    }

    @Test
    void summarizeWithAnAgentReplacesTheMiddleRangeWithASummaryEntry() {
        // budget generous enough that one summarisation of the middle brings the window
        // back under it — so no follow-up eviction pass removes the fresh summary entry.
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                140, EvictionPolicy.SUMMARIZE, summarizer("SUMMARY OF EARLIER CONTEXT", true, false));
        fill(m, 12);

        List<MemoryEntry> w = m.workingMemory();
        assertTrue(w.size() < 12, "the middle was collapsed");
        assertTrue(w.stream().anyMatch(e -> hasLabel(e, "context_summary")
                        && "SUMMARY OF EARLIER CONTEXT".equals(e.content())),
                "the evicted middle was replaced by the agent's summary");
    }

    @Test
    void summarizeFallsBackWhenTheAgentFailsOrThrows() {
        SlidingWindowMemoryManager fails = new SlidingWindowMemoryManager(
                60, EvictionPolicy.SUMMARIZE, summarizer("x", false, false));
        SlidingWindowMemoryManager boom = new SlidingWindowMemoryManager(
                60, EvictionPolicy.SUMMARIZE, summarizer("x", false, true));
        fill(fails, 12);
        fill(boom, 12);

        assertFalse(fails.workingMemory().stream().anyMatch(e -> hasLabel(e, "context_summary")));
        assertFalse(boom.workingMemory().stream().anyMatch(e -> hasLabel(e, "context_summary")));
        assertTrue(boom.workingMemory().size() < 12, "still evicted despite the exception");
    }

    @Test
    void offloadUpsertsEvictedEntriesBeforeDiscardingThem() {
        RecordingStore store = new RecordingStore();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                60, EvictionPolicy.DROP_OLDEST, null, store, EMBED, "agent-7");
        fill(m, 12);

        assertFalse(store.upserted.isEmpty(), "evicted entries were offloaded");
        assertTrue(store.upserted.stream().anyMatch(c -> c.contains("message number 0")),
                "the oldest evicted message reached the episodic store");
    }

    @Test
    void offloadIsInertWithoutAStore() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(60, EvictionPolicy.DROP_OLDEST);
        fill(m, 12);   // must simply not throw
        assertTrue(m.workingMemory().size() < 12);
    }

    @Test
    void recallRelevantPullsMatchesToTheHeadTaggedRecalled() {
        RecordingStore store = new RecordingStore();
        store.nextSearchResult = List.of(
                MemoryEntry.of("user", "an earlier relevant fact"),
                MemoryEntry.of("assistant", "an earlier relevant answer"));
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7");
        m.appendToWorkingMemory("user", "current question");

        m.recallRelevant("something relevant", 5);

        List<MemoryEntry> w = m.workingMemory();
        assertEquals("an earlier relevant fact", w.get(0).content());
        assertTrue(hasLabel(w.get(0), "recalled"));
        assertEquals("current question", w.get(2).content(), "recalled entries sit ahead of the live window");
    }

    @Test
    void recallRelevant_slotsRecalledEntriesBehindTheSystemPrompt_neverAtIndexZero() {
        RecordingStore store = new RecordingStore();
        store.nextSearchResult = List.of(MemoryEntry.of("user", "an earlier relevant fact"));
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7");
        m.appendToWorkingMemory("system", "agent system prompt");
        m.appendToWorkingMemory("user", "current question");

        m.recallRelevant("something relevant", 5);

        List<MemoryEntry> w = m.workingMemory();
        assertEquals("agent system prompt", w.get(0).content(),
                "the system prompt must stay at index 0 — recalled entries at the head would "
                + "silence the strategies' tool-catalog enhancement");
        assertEquals("an earlier relevant fact", w.get(1).content(),
                "recalled episodes slot in just behind the system prompt");
        assertEquals("current question", w.get(2).content());
    }

    @Test
    void recallRelevantIsANoOpForBlankQueryNonPositiveLimitOrNoStore() {
        RecordingStore store = new RecordingStore();
        store.nextSearchResult = List.of(MemoryEntry.of("user", "should not appear"));
        SlidingWindowMemoryManager withStore = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7");
        withStore.appendToWorkingMemory("user", "q");

        withStore.recallRelevant("   ", 5);
        withStore.recallRelevant("real query", 0);
        assertEquals(1, withStore.workingMemory().size());

        SlidingWindowMemoryManager noStore = new SlidingWindowMemoryManager(0, EvictionPolicy.DROP_MIDDLE);
        noStore.appendToWorkingMemory("user", "q");
        noStore.recallRelevant("real query", 5);
        assertEquals(1, noStore.workingMemory().size());
    }

    private static boolean hasLabel(MemoryEntry e, String label) {
        return e.metadata() instanceof io.ara.core.memory.EpisodeLabel el && label.equals(el.value());
    }
}
