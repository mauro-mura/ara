package io.ara.runtime.eval;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * One execution of one case during an eval: what the agent answered and how it was judged.
 *
 * <p>{@link io.ara.core.eval.EvalResult} keeps only aggregates — a mean and a standard deviation
 * per case — because that is what a verdict needs. It cannot answer the questions a person asks
 * of a failing suite: <em>what did the agent actually say</em>, <em>was it judged wrong or did it
 * never answer</em>, <em>why did the judge score it 0.3</em>. A mean of 0.33 over three runs is
 * identical whether two runs timed out or two runs were wrong, and only the sample tells them
 * apart. This record is the per-run evidence, delivered to an {@link EvalSampleListener} as the
 * run happens so a caller that wants it can keep it and one that does not pays nothing.
 *
 * @param evalId         the id the resulting {@code EvalResult} will carry, so samples join to it
 * @param caseId         the case executed
 * @param holdout        whether the case is a hold-out case
 * @param run            zero-based index of this execution within the case's {@code n} runs
 * @param correlationId  the id the task was run under, which is also the {@code runId} of the
 *                       agent's trace spans — the join key to the trace
 * @param succeeded      whether the agent completed ({@code false}: no answer was produced)
 * @param failureReason  why it did not complete, {@code null} when it did
 * @param content        the agent's answer, empty when it did not complete
 * @param score          the score recorded for this run, in {@code [0, 1]}
 * @param rationale      the evaluator's explanation; for a run that never completed, the failure
 *                       reason, so the field is never silently empty on a score of zero
 * @param metadata       the evaluator's extra data (for a judge, the raw reply when it could not
 *                       be read)
 * @param elapsed        the agent's wall-clock time for this run
 */
public record EvalSample(
        String evalId,
        String caseId,
        boolean holdout,
        int run,
        String correlationId,
        boolean succeeded,
        String failureReason,
        String content,
        double score,
        String rationale,
        Map<String, String> metadata,
        Duration elapsed
) {
    public EvalSample {
        Objects.requireNonNull(evalId, "evalId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        content = Objects.requireNonNullElse(content, "");
        rationale = Objects.requireNonNullElse(rationale, "");
        metadata = Map.copyOf(Objects.requireNonNullElse(metadata, Map.of()));
        elapsed = Objects.requireNonNullElse(elapsed, Duration.ZERO);
    }
}
