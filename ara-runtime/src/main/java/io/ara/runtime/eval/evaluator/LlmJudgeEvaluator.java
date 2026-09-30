package io.ara.runtime.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real {@code "judge"} strategy (ADR-0059 D2 / ADR-0070 / ADR-0080 D4) that {@code
 * PlaceholderJudgeEvaluator}'s own javadoc calls for: "a runtime-side caller registers a
 * real judge over the {@code judge} id to replace this". Wraps a judge {@link AraAgent} —
 * any agent, real transport or test fake, this class does not care which — and asks it to
 * score the case's output against a rubric.
 *
 * <p><b>Advisory, and the guarantee lives in the caller</b> (ADR-0059 D2). A judge case is
 * meant to reach the ADR-0059 cascade as non-blocking, so a bad grade puts a human in
 * {@code NeedsReview} rather than vetoing a promotion. This class neither decides nor enforces
 * that, and an earlier version of this comment claimed it did: {@code
 * DefaultEvalRunner.isBlocking} consults the strategy name <em>only</em> when
 * {@code evaluationConfig["blocking"]} is absent, so a caller that writes
 * {@code "blocking": "true"} on a judge case gets a live veto with nothing here objecting.
 *
 * <p>On the path that matters the key is already downgraded before the runner ever sees the
 * case — a console criterion's effective blocking is {@code blocking && expected != null},
 * because a veto only a probabilistic evaluator can trigger is the "judge hacking" shape
 * ADR-0059 D2 exists to exclude. So the guarantee is real, and it is also entirely somebody
 * else's: a caller that omits the key gets {@link DefaultEvalRunner#isBlocking}'s name-based
 * default, and a caller that sets it to anything but {@code "false"} gets a veto.
 *
 * <p><b>Two ways to fail, neither fatal and neither a vote.</b> A judge agent that does not
 * complete scores {@code 0.0} ({@link EvaluationResult#error}); a reply carrying no readable
 * {@code SCORE:} line scores {@link #UNPARSEABLE_SCORE}. Both land under
 * {@link DefaultEvalRunner#JUDGE_ADVISORY_THRESHOLD}, so both end in {@code NeedsReview} —
 * the escalation path ADR-0059 D2 asks for — and neither crashes the run. The two numbers are
 * not the same and must not be made so: {@link #UNPARSEABLE_SCORE} is a contract with callers
 * that retry an unreadable grade, which detect that condition by the {@code judge_raw_reply}
 * metadata this class sets on that path and on no other, and which rely on the grade being
 * middling rather than a zero. Unifying the two would renegotiate it silently, across a repo
 * boundary nothing in this module can see.
 *
 * <p><b>Rubric</b>: {@link EvalCase#evaluationConfig()}'s {@code "rubric"} entry, or a
 * generic "does the output correctly and completely answer the input" instruction when
 * absent. The judge is asked to answer in exactly the shape {@link #SCORE_LINE} parses.
 */
public final class LlmJudgeEvaluator implements EvaluationStrategy {

    /**
     * Neutral-low score for a judge reply this evaluator could not parse — advisory, not a veto.
     *
     * <p>The value is pinned just under {@link DefaultEvalRunner#JUDGE_ADVISORY_THRESHOLD}, and
     * that ordering is the only reason an unreadable grade reaches {@code NeedsReview} rather
     * than a canary. Nothing enforces it but a test that runs the whole cascade
     * ({@code DefaultEvalRunnerTest.anUnreadableJudgeReply_needsReview_andNeverPromotes}): raise
     * this to {@code 0.7}, or lower the threshold to {@code 0.4}, and a formatting wobble
     * promotes.
     *
     * <p>Not to be folded to {@code 0.0} to match {@link EvaluationResult#error}. Callers
     * outside this module retry precisely this path — they detect it by the
     * {@code judge_raw_reply} metadata key set below, which no other branch sets — and they
     * read {@code 0.5} as "not a vote" where {@code 0.0} would read as a total failure.
     */
    public static final double UNPARSEABLE_SCORE = 0.5;

    private static final String DEFAULT_RUBRIC =
            "Does the output correctly and completely answer the input? Consider correctness first, "
                    + "then completeness and clarity.";

    private static final Pattern SCORE_LINE =
            Pattern.compile("(?im)^\\s*SCORE\\s*:\\s*(\\d*\\.?\\d+)\\s*$");

    private final AraAgent judge;

    public LlmJudgeEvaluator(AraAgent judge) {
        this.judge = Objects.requireNonNull(judge, "judge must not be null");
    }

    @Override
    public String strategyId() {
        return "judge";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(evalCase, "evalCase must not be null");

        String rubric = evalCase.evaluationConfig().getOrDefault("rubric", DEFAULT_RUBRIC);
        AgentResponse verdict = judge.execute(AgentTask.of(prompt(rubric, evalCase.input(), response.content())));

        if (!verdict.isSuccess()) {
            return EvaluationResult.error("judge agent did not complete: " + verdict.failureReason());
        }
        Double score = parseScore(verdict.content());
        if (score == null) {
            return new EvaluationResult(true, UNPARSEABLE_SCORE,
                    "judge reply did not contain a parseable 'SCORE: <0..1>' line — advisory neutral score",
                    java.util.Map.of("judge_raw_reply", verdict.content()));
        }
        String rationale = stripScoreLine(verdict.content());
        return new EvaluationResult(score >= 0.5, score, rationale, java.util.Map.of());
    }

    private static String prompt(String rubric, String taskInput, String candidateOutput) {
        return """
                You are an evaluation judge. Score the CANDIDATE OUTPUT for the TASK against \
                the RUBRIC on a continuous scale from 0.0 (fails completely) to 1.0 (fully \
                satisfies the rubric).

                TASK:
                %s

                CANDIDATE OUTPUT:
                %s

                RUBRIC:
                %s

                Reply with a brief rationale, then a final line in exactly this form:
                SCORE: <a number between 0.0 and 1.0>
                """.formatted(taskInput, candidateOutput, rubric);
    }

    /**
     * The value of the <em>last</em> {@code SCORE:} line in {@code judgeReply}, held to
     * {@code [0, 1]}; {@code null} when the reply carries no such line.
     *
     * <p>Two guards a first reading suggests are deliberately not here. The reply cannot be
     * null — {@link AgentResponse} already rejects a null content — and the pattern captures
     * digits and at most one dot, so whatever it captures is a string {@code Double.parseDouble}
     * accepts. Both checks could only ever be dead, and a guard that cannot fire is worse than
     * none: it reads as a real failure mode someone thought about.
     */
    private static Double parseScore(String judgeReply) {
        Matcher matcher = SCORE_LINE.matcher(judgeReply);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        if (last == null) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, Double.parseDouble(last)));
    }

    private static String stripScoreLine(String judgeReply) {
        return SCORE_LINE.matcher(judgeReply).replaceAll("").strip();
    }
}
