package io.ara.runtime.workflow;

import io.ara.core.budget.Spend;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a {@link DataflowScheduler} run: the append-only journal, plus whether
 * the run completed. ADR-052 calls the journal the trace, the checkpoint, and the
 * serialization order all at once — one artifact serving three purposes that would
 * otherwise be three separate types.
 *
 * <p>{@code ok}/{@code failureReason} is a placeholder pair, not a finished status model:
 * a node failure and a node suspension are both reported as {@code ok == false} today,
 * distinguishable only by reading {@code failureReason}. Giving suspension its own status
 * — one a caller can branch on without string-matching — is ADR-052 D6's job, once there
 * is something to resume a suspended run *into*; adding that distinction here now, with
 * nothing yet consuming it, would be exactly the premature abstraction the coding
 * guidelines ask to avoid.
 *
 * @param state every {@link WorkflowNode.Write} recorded so far, merged through the
 *              graph's declared reducers (ADR-052 D3) — present even on a failed or
 *              partial run, reflecting whatever was written before the run stopped.
 * @param governedSpend the final tally of the run's own {@code RunBudget} (ADR-054 D6) —
 *              agent-shaped nodes' real cost <em>and</em> the declared {@link
 *              WorkflowNode#cost()} of opaque ones — or {@code null} when the run had no
 *              governor. Read it through {@link #spend()}.
 */
public record WorkflowResult(List<JournalEntry> journal, boolean ok, String failureReason,
                             Map<String, Object> state, Spend governedSpend) {

    public WorkflowResult {
        Objects.requireNonNull(journal, "journal must not be null");
        Objects.requireNonNull(state, "state must not be null");
        journal = List.copyOf(journal);
        state = Map.copyOf(state);
    }

    /** Backwards-compatible constructor: the run reports no governed spend. */
    public WorkflowResult(List<JournalEntry> journal, boolean ok, String failureReason, Map<String, Object> state) {
        this(journal, ok, failureReason, state, null);
    }

    /** This result carrying the final tally of the run's budget. */
    public WorkflowResult withGovernedSpend(Spend spend) {
        return new WorkflowResult(journal, ok, failureReason, state, spend);
    }

    /**
     * What this run spent: the budget's own tally when the run was governed (it counts every
     * node, opaque ones included), otherwise the sum of the agent-shaped nodes' responses
     * ({@link #totalSpend()}), or empty when nothing measurable ran.
     */
    public Optional<Spend> spend() {
        return governedSpend != null ? Optional.of(governedSpend) : totalSpend();
    }

    /** Backwards-compatible constructor: no shared state (ADR-052 D3). */
    public WorkflowResult(List<JournalEntry> journal, boolean ok, String failureReason) {
        this(journal, ok, failureReason, Map.of());
    }

    /** How many occurrences of this node reached a {@link JournalEntry.Finished} entry, of any outcome. */
    public long firedTimes(String nodeId) {
        return journal.stream()
                .filter(JournalEntry.Finished.class::isInstance)
                .filter(e -> e.nodeId().equals(nodeId))
                .count();
    }

    /** The first {@link JournalEntry.Finished} entry for this node. */
    public JournalEntry.Finished firstOf(String nodeId) {
        return journal.stream()
                .filter(JournalEntry.Finished.class::isInstance)
                .map(JournalEntry.Finished.class::cast)
                .filter(e -> e.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(nodeId + " never finished"));
    }

    /** The journal as {@code "nodeId#occurrence"} tokens, in write order — for assertions and logs. */
    public List<String> order() {
        return journal.stream().map(JournalEntry::toString).toList();
    }

    /**
     * Prompt tokens consumed by the run's agent-shaped nodes (ADR-052 D2), summed from
     * each {@link NodeOutcome.Completed}'s {@link io.ara.core.agent.AgentResponse}. Zero
     * for a graph of opaque nodes — there is no agent to report from.
     */
    public int totalPromptTokens() {
        return agentResponses().mapToInt(r -> r.inputTokens()).sum();
    }

    /** Output tokens consumed by the run's agent-shaped nodes — the {@link #totalPromptTokens()} counterpart. */
    public int totalOutputTokens() {
        return agentResponses().mapToInt(r -> r.outputTokens()).sum();
    }

    /**
     * The spend the run's agent-shaped nodes drew, summed across occurrences, or empty
     * when the graph has no agent-shaped node. Money carries its own currency and each
     * response's totals are added axis by axis — a mixed-currency graph is a caller error
     * {@link io.ara.core.common.Money#plus} surfaces, not one to paper over here.
     */
    public Optional<Spend> totalSpend() {
        return agentResponses()
                .map(r -> Spend.of(r.estimatedCost(), r.totalTokens(), 1))
                .reduce(Spend::plus);
    }

    private java.util.stream.Stream<io.ara.core.agent.AgentResponse> agentResponses() {
        return journal.stream()
                .filter(JournalEntry.Finished.class::isInstance)
                .map(JournalEntry.Finished.class::cast)
                .map(JournalEntry.Finished::outcome)
                .filter(NodeOutcome.Completed.class::isInstance)
                .map(NodeOutcome.Completed.class::cast)
                .map(NodeOutcome.Completed::response)
                .filter(Objects::nonNull);
    }
}
