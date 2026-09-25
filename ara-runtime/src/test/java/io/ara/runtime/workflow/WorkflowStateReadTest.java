package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D3, the read side — a node declares {@code reads(...)} and its agent gets an
 * immutable snapshot of just those shared-state keys through {@code RunContext.state()} —
 * and D5 control #7, the build-time check that every declared key has a writer upstream.
 */
class WorkflowStateReadTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /** A deterministic agent that reports what it can see of {@code key} in its RunState. */
    private static AraAgent seeing(String name, String key) {
        return AraAgents.deterministic(AgentId.of(name),
                task -> "saw=" + task.runContext().state().get(key, Object.class).map(String::valueOf).orElse("NOTHING"));
    }

    private static String outputOf(WorkflowResult result, String nodeId) {
        return result.journal().stream()
                .filter(e -> e instanceof JournalEntry.Finished f && f.nodeId().equals(nodeId))
                .map(e -> ((JournalEntry.Finished) e).outcome())
                .filter(o -> o instanceof NodeOutcome.Completed)
                .map(o -> ((NodeOutcome.Completed) o).content())
                .findFirst()
                .orElseThrow(() -> new AssertionError("node '" + nodeId + "' has no completed entry in the journal"));
    }

    // ── the read side ────────────────────────────────────────────────────────────

    @Test
    void anAgentReadsWhatAPredecessorWrote() {
        WorkflowResult result = Workflow.of()
                .node("writer", in -> "the-draft").writes("writer", "draft", out -> out)
                .agent("reader", seeing("reader", "draft")).reads("reader", "draft")
                .edge("writer", "reader")
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("saw=the-draft", outputOf(result, "reader"));
    }

    @Test
    void aKeyThatWasNotDeclared_isInvisible() {
        WorkflowResult result = Workflow.of()
                .node("writer", in -> "A").writes("writer", "a", out -> out)
                .node("writer2", in -> "B").writes("writer2", "b", out -> out)
                .agent("reader", seeing("reader", "b")).reads("reader", "a")   // declares a, looks at b
                .edge("writer", "writer2").edge("writer2", "reader")
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("saw=NOTHING", outputOf(result, "reader"));
    }

    @Test
    void afterAJoin_theReaderSeesTheReducedValue_ofBothBranches() {
        AraAgent judge = AraAgents.deterministic(AgentId.of("judge"), task -> {
            List<?> findings = task.runContext().state().get("findings", List.class).orElse(List.of());
            return "n=" + findings.size();
        });

        WorkflowResult result = Workflow.of()
                .node("split", in -> "start")
                .node("left",  in -> "L").writes("left",  "findings", out -> List.of(out))
                .node("right", in -> "R").writes("right", "findings", out -> List.of(out))
                .agent("judge", judge).reads("judge", "findings")
                .composer("judge", parts -> String.join("|", parts))
                .edge("split", "left").edge("split", "right")
                .edge("left", "judge").edge("right", "judge")
                .reduce("findings", Reducers.concatLists())
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("n=2", outputOf(result, "judge"), "the join is a barrier: both writes have landed");
    }

    /**
     * Two parallel writers merge in declaration order, never completion order: "left" is
     * declared first, so a non-commutative reducer yields [L, R] even when "right" finishes
     * first. Run both ways with a latch that forces the finishing order.
     */
    @Test
    void concurrentWrites_mergeInDeclarationOrder_whateverOrderTheyFinishIn() throws Exception {
        for (boolean rightFinishesFirst : new boolean[] {false, true}) {
            java.util.concurrent.CountDownLatch firstDone = new java.util.concurrent.CountDownLatch(1);
            Function<String, String> left = in -> {
                if (rightFinishesFirst) awaitQuietly(firstDone);
                String out = "L";
                if (!rightFinishesFirst) firstDone.countDown();
                return out;
            };
            Function<String, String> right = in -> {
                if (!rightFinishesFirst) awaitQuietly(firstDone);
                String out = "R";
                if (rightFinishesFirst) firstDone.countDown();
                return out;
            };
            AraAgent judge = AraAgents.deterministic(AgentId.of("judge"),
                    task -> String.valueOf(task.runContext().state().get("findings", List.class).orElse(List.of())));

            WorkflowResult result = Workflow.of()
                    .node("split", in -> "start")
                    .node("left", left).writes("left", "findings", out -> List.of(out))
                    .node("right", right).writes("right", "findings", out -> List.of(out))
                    .agent("judge", judge).reads("judge", "findings")
                    .composer("judge", parts -> String.join("|", parts))
                    .edge("split", "left").edge("split", "right")
                    .edge("left", "judge").edge("right", "judge")
                    .reduce("findings", Reducers.concatLists())
                    .build()
                    .run("go", pool);

            assertTrue(result.ok(), result.failureReason());
            assertEquals("[L, R]", outputOf(result, "judge"), "rightFinishesFirst=" + rightFinishesFirst);
            assertEquals(List.of("L", "R"), result.state().get("findings"), "rightFinishesFirst=" + rightFinishesFirst);
        }
    }

    /** Causal order still wins over declaration order: in a loop the later lap's write is the later one. */
    @Test
    void writesAlongACycle_keepTheirCausalOrder() {
        int[] laps = {0};
        WorkflowResult result = Workflow.of()
                .node("start", in -> in)
                // "review" declared BEFORE "draft": declaration order alone would put every
                // draft write last and answer "d2"; only causal order gives "r2".
                .routingNode("review", in -> "r" + laps[0], out -> ++laps[0] < 3 ? Set.of("draft") : Set.of())
                .writes("review", "last", out -> out)
                .node("draft", in -> "d" + laps[0]).writes("draft", "last", out -> out)
                .edge("start", "draft").edge("draft", "review").backEdge("review", "draft")
                .reduce("last", Reducers.lastWriteWins())
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("r2", result.state().get("last"), "the final lap's review is the causally last write");
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aNodeCannotWriteToTheSharedStateThroughItsRunState() {
        AraAgent scribbler = AraAgents.deterministic(AgentId.of("scribbler"), task -> {
            task.runContext().state().put("hack", "x");
            return "done";
        });

        WorkflowResult result = Workflow.of()
                .node("writer", in -> "seed").writes("writer", "k", out -> out)
                .agent("scribbler", scribbler).reads("scribbler", "k")
                .edge("writer", "scribbler")
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertFalse(result.state().containsKey("hack"), "the only way to contribute state is a declared write");
        assertEquals("seed", result.state().get("k"));
    }

    @Test
    void aMapOverSourceCountsAsTheWriterOfItsCollectedKey() {
        WorkflowResult result = Workflow.of()
                .node("plan", in -> "a,b,c")
                .mapOver("plan", "worker", out -> List.of(out.split(",")), item -> item.toUpperCase(),
                        "results", 10, AgentChain.FailurePolicy.FAIL_FAST)
                .agent("summarise", AraAgents.deterministic(AgentId.of("summarise"),
                        task -> "n=" + task.runContext().state().get("results", List.class).orElse(List.of()).size()))
                .reads("summarise", "results")
                .edge("plan", "summarise")
                .build()
                .run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("n=3", outputOf(result, "summarise"));
    }

    // ── declaring reads ──────────────────────────────────────────────────────────

    @Test
    void anOpaqueNodeCannotDeclareReads() {
        Workflow.Builder b = Workflow.of().node("plain", in -> "x");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> b.reads("plain", "k"));
        assertTrue(e.getMessage().contains("not agent-shaped"), e.getMessage());
    }

    @Test
    void readsNamingAnUnknownNode_orABlankKey_isRejected() {
        Workflow.Builder b = Workflow.of().agent("a", seeing("a", "k"));

        assertThrows(IllegalArgumentException.class, () -> b.reads("ghost", "k"));
        assertThrows(IllegalArgumentException.class, () -> b.reads("a", " "));
        assertThrows(IllegalArgumentException.class, () -> b.reads("a", (String) null));
    }

    // ── D5 control #7: a reader needs a writer upstream ─────────────────────────

    @Test
    void controlSeven_aKeyNobodyWrites_isRejectedAtBuild() {
        Workflow.Builder b = Workflow.of()
                .node("writer", in -> "x").writes("writer", "draft", out -> out)
                .agent("reader", seeing("reader", "drfat")).reads("reader", "drfat")   // typo
                .edge("writer", "reader");

        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("control #7"), e.getMessage());
        assertTrue(e.getMessage().contains("'drfat'"), e.getMessage());
        assertTrue(e.getMessage().contains("nothing writes"), e.getMessage());
    }

    @Test
    void controlSeven_aWriterDownstreamOfItsReader_isRejectedAtBuild() {
        Workflow.Builder b = Workflow.of()
                .agent("reader", seeing("reader", "draft")).reads("reader", "draft")
                .node("writer", in -> "x").writes("writer", "draft", out -> out)
                .edge("reader", "writer");   // the writer comes AFTER the reader

        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("control #7"), e.getMessage());
        assertTrue(e.getMessage().contains("none of which has a path"), e.getMessage());
    }

    @Test
    void controlSeven_aWriterConcurrentWithTheReader_isRejectedAtBuild() {
        // "side" writes 'draft' on a branch parallel to "reader": whether its write has
        // landed when "reader" starts would depend on timing.
        Workflow.Builder b = Workflow.of()
                .node("split", in -> "go")
                .node("writer", in -> "x").writes("writer", "draft", out -> out)
                .node("side", in -> "y").writes("side", "draft", out -> out)
                .agent("reader", seeing("reader", "draft")).reads("reader", "draft")
                .edge("split", "writer").edge("split", "side").edge("writer", "reader")
                .reduce("draft", Reducers.lastWriteWins());

        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("control #7"), e.getMessage());
        assertTrue(e.getMessage().contains("'side'"), e.getMessage());
        assertTrue(e.getMessage().contains("concurrent"), e.getMessage());
    }

    @Test
    void controlSeven_aWriterDownstreamOfTheReader_isNotConcurrent_andBuilds() {
        Workflow wf = Workflow.of()
                .node("writer", in -> "x").writes("writer", "draft", out -> out)
                .agent("reader", seeing("reader", "draft")).reads("reader", "draft")
                .node("after", in -> "z").writes("after", "draft", out -> out)
                .edge("writer", "reader").edge("reader", "after")
                .reduce("draft", Reducers.lastWriteWins())
                .build();

        WorkflowResult result = wf.run("go", pool);
        assertTrue(result.ok(), result.failureReason());
        assertEquals("saw=x", outputOf(result, "reader"));
    }

    @Test
    void controlSeven_aNodeReadingItsOwnKey_isAcceptedOnlyWhenACycleBringsItBack() {
        Workflow.Builder noCycle = Workflow.of()
                .agent("refine", seeing("refine", "draft")).reads("refine", "draft")
                .writes("refine", "draft", out -> out);
        assertThrows(IllegalStateException.class, noCycle::build);

        Workflow withCycle = Workflow.of()
                .agent("refine", seeing("refine", "draft")).reads("refine", "draft")
                .writes("refine", "draft", out -> out)
                .backEdge("refine", "refine")
                .build();
        assertEquals(1, withCycle.graph().nodes().size());
    }

    @Test
    void aWorkflowThatDeclaresNoReads_isUnaffected() {
        Function<String, String> body = in -> in;
        Workflow wf = Workflow.of().node("a", body).node("b", body).edge("a", "b").build();

        assertTrue(wf.run("x", pool).ok());
    }
}
