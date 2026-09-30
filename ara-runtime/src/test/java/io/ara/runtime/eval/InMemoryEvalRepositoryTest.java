package io.ara.runtime.eval;

import io.ara.core.eval.CaseStats;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvalResult;
import io.ara.core.eval.EvalSuite;
import io.ara.core.eval.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0070 — {@link InMemoryEvalRepository}: suites/cases/results by id, cases in a total
 * order, and the hold-out partition {@code EvalRunner}'s two methods rely on.
 */
class InMemoryEvalRepositoryTest {

    @Test
    void suitesAndCases_roundTrip_andCasesAreOrderedBySeqNo() {
        EvalRepository repo = EvalRepository.inMemory();
        repo.saveSuite(new EvalSuite("s1", "Suite One", "desc"));
        repo.saveCase(EvalCase.curated("c2", "s1", "second", "exact_match", 2));
        repo.saveCase(EvalCase.curated("c1", "s1", "first", "exact_match", 1));
        repo.saveCase(EvalCase.curated("other", "s2", "x", "regex", 1));

        assertTrue(repo.findSuite("s1").isPresent());
        assertEquals(List.of("c1", "c2"), repo.findCases("s1").stream().map(EvalCase::caseId).toList());
        assertEquals(1, repo.findCases("s2").size());
        assertTrue(repo.findCases("unknown").isEmpty());
    }

    /**
     * Cases sharing a {@code seqNo} — what every case derived from a production failure looks
     * like, since {@code RegressionCaseBuilder} numbers them all 0 — still come back in one
     * fixed order. Without the {@code caseId} tiebreak the order is the hash of the caseId, so
     * the same corpus would name a different culprit in its verdict run after run.
     */
    @Test
    void casesSharingASeqNo_comeBackInCaseIdOrder() {
        EvalRepository repo = EvalRepository.inMemory();
        repo.saveCase(EvalCase.curated("c3", "s1", "third", "exact_match", 0));
        repo.saveCase(EvalCase.curated("c1", "s1", "first", "exact_match", 0));
        repo.saveCase(EvalCase.curated("c2", "s1", "second", "exact_match", 0));

        assertEquals(List.of("c1", "c2", "c3"),
                repo.findCases("s1").stream().map(EvalCase::caseId).toList());
    }

    @Test
    void findCases_partitionsByHoldout() {
        EvalRepository repo = EvalRepository.inMemory();
        repo.saveCase(EvalCase.curated("c1", "s1", "a", "exact_match", 1));
        repo.saveCase(EvalCase.curated("c2", "s1", "b", "exact_match", 2).asHoldout());
        repo.saveCase(EvalCase.curated("c3", "s1", "c", "exact_match", 3).asHoldout());

        assertEquals(List.of("c1"), repo.findCases("s1", false).stream().map(EvalCase::caseId).toList());
        assertEquals(List.of("c2", "c3"), repo.findCases("s1", true).stream().map(EvalCase::caseId).toList());
    }

    @Test
    void results_areStoredByIdAndQueryableBySpecHash() {
        EvalRepository repo = EvalRepository.inMemory();
        EvalResult r1 = result("e1", "spec-A");
        EvalResult r2 = result("e2", "spec-A");
        EvalResult r3 = result("e3", "spec-B");
        repo.saveResult(r1);
        repo.saveResult(r2);
        repo.saveResult(r3);

        assertEquals("spec-A", repo.findResult("e1").orElseThrow().specHash());
        assertEquals(2, repo.findResultsForSpec("spec-A").size());
        assertEquals(1, repo.findResultsForSpec("spec-B").size());
        assertTrue(repo.findResultsForSpec("spec-none").isEmpty());
    }

    private static EvalResult result(String evalId, String specHash) {
        CaseStats cs = CaseStats.of("c1", false, 0.8, 0.9, 0.7);
        return new EvalResult(evalId, specHash, "s1", 3, Map.of("c1", cs),
                Map.of(), List.of(), new Verdict.Reject("baseline"));
    }
}
