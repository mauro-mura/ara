package io.ara.core.eval;

import io.ara.core.budget.Spend;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The aggregated outcome of running an eval suite against one agent spec over N≥3 runs
 * per case (ADR-0070 D4).
 *
 * @param evalId       stable id of this eval run
 * @param specHash     the {@code AgentSpec} that was evaluated (ADR-0065) — a hash string,
 *                     so this type has no dependency on where {@code AgentSpec} itself lives
 * @param suiteId      the {@link EvalSuite} that was run
 * @param nRunsPerCase N used (≥ {@link CaseStats#MIN_RUNS})
 * @param perCase      {@link CaseStats} keyed by {@code caseId}, in the order the caller
 *                     measured them — the corpus order a repository returns, sorted by
 *                     {@link EvalCase#seqNo()} — which the verdict cascade reads in turn to
 *                     name the case that decided it
 * @param perTag       mean score aggregated per tag declared on the cases (ADR-0070 D5), in
 *                     the order the tags were first met on that corpus
 * @param regressions  {@code caseId}s that passed before this run and fail now
 * @param verdict      the {@link Verdict} from the ADR-0059 cascade
 * @param runCosts     the {@link Spend} of each individual run, across all evaluated cases,
 *                     as measured per run by a {@code RunBudget} (ADR-054 D6). Empty when
 *                     the runner did not track cost. ADR-0085 D1 reads the median of this
 *                     to size a topology's relative cost. Assumes a consistent currency
 *                     (true for one agent / provider).
 */
public record EvalResult(
        String                 evalId,
        String                 specHash,
        String                 suiteId,
        int                    nRunsPerCase,
        Map<String, CaseStats> perCase,
        Map<String, Double>    perTag,
        List<String>           regressions,
        Verdict                verdict,
        List<Spend>            runCosts
) {

    public EvalResult {
        Objects.requireNonNull(evalId, "evalId must not be null");
        Objects.requireNonNull(specHash, "specHash must not be null");
        Objects.requireNonNull(suiteId, "suiteId must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        if (nRunsPerCase < CaseStats.MIN_RUNS) {
            throw new IllegalArgumentException(
                    "nRunsPerCase must be >= " + CaseStats.MIN_RUNS + ", got: " + nRunsPerCase);
        }
        perCase     = ordered(perCase);
        perTag      = ordered(perTag);
        regressions = List.copyOf(Objects.requireNonNullElse(regressions, List.of()));
        runCosts    = List.copyOf(Objects.requireNonNullElse(runCosts, List.of()));
    }

    /**
     * An unmodifiable copy that keeps the caller's iteration order.
     *
     * <p>{@code Map.copyOf} is the obvious call and is what these two components used to take.
     * It promises no iteration order and does not keep the source's: {@code perCase} inserted
     * as {@code c1, c2, c3} reads back {@code c3, c2, c1}, because the entries land in an
     * order derived from their hashes. The corpus order the runner measured in would be
     * rehashed away here, and the verdict cascade names the <em>first</em> failing case — so
     * which case an eval blames would be decided by a hash rather than by the suite, and
     * change between two runs of the same corpus.
     *
     * <p>Trade-off accepted: {@code Map.copyOf} also rejected null keys and values while
     * copying, {@code LinkedHashMap} does not, so a null value now surfaces at the first read
     * instead of at construction. A null {@code CaseStats} is a caller bug that throws either
     * way, and the loop that would restore the early check costs more than the check is worth.
     */
    private static <K, V> Map<K, V> ordered(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNullElse(source, Map.of())));
    }

    /** Backwards-compatible constructor for callers that do not carry per-run cost. */
    public EvalResult(String evalId, String specHash, String suiteId, int nRunsPerCase,
                      Map<String, CaseStats> perCase, Map<String, Double> perTag,
                      List<String> regressions, Verdict verdict) {
        this(evalId, specHash, suiteId, nRunsPerCase, perCase, perTag, regressions, verdict, List.of());
    }

    /** {@code true} when every case's {@link CaseStats} was aggregated over enough runs. */
    public boolean hasEnoughRuns() {
        return perCase.values().stream().allMatch(s -> s.n() >= CaseStats.MIN_RUNS);
    }
}
