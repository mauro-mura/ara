package io.ara.runtime.eval;

import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvalResult;
import io.ara.core.eval.EvalSuite;

import java.util.List;
import java.util.Optional;

/**
 * Store for eval suites, cases and results (ADR-0070) — the relocation of ADR-019's
 * {@code BenchmarkRepository}, trimmed to what this backlog needs. Same in-memory-default
 * idiom as {@code TraceStore} / {@code PromptCatalogRepository}; durable persistence is an
 * implementation concern, not decided by the ADR.
 */
public interface EvalRepository {

    void saveSuite(EvalSuite suite);

    Optional<EvalSuite> findSuite(String suiteId);

    void saveCase(EvalCase evalCase);

    /**
     * Cases of {@code suiteId} in a <em>total</em> order: by {@link EvalCase#seqNo()}, ties
     * broken by {@code caseId}. The tiebreak is what makes it total — a corpus holds several
     * cases with the same {@code seqNo} whenever they were all derived from production
     * failures — and an eval whose case order could shift between two runs of the same corpus
     * would report a different culprit in its verdict than the one it measured first.
     */
    List<EvalCase> findCases(String suiteId);

    /** Cases of {@code suiteId} filtered by hold-out flag — how {@code EvalRunner}'s two methods partition the suite. */
    List<EvalCase> findCases(String suiteId, boolean holdout);

    void saveResult(EvalResult result);

    Optional<EvalResult> findResult(String evalId);

    /**
     * Every result recorded for {@code specHash}, in unspecified order.
     *
     * <p>No chronological order is derivable from this interface: an {@link EvalResult} carries
     * no timestamp and its {@code evalId} is a random UUID, so a caller that needs "the most
     * recent result" — which is what a baseline lookup wants — must track the one it means
     * rather than take the last element of this list.
     */
    List<EvalResult> findResultsForSpec(String specHash);

    static EvalRepository inMemory() {
        return new InMemoryEvalRepository();
    }
}
