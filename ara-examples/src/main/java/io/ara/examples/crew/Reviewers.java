package io.ara.examples.crew;

import io.ara.examples.support.Ansi;

import java.util.List;

/**
 * The crew's static description: who the reviewers are, what each one is told to look for,
 * which tool it calls, and the colour its lines are printed in.
 *
 * <p>This is one source of truth shared by three consumers — {@link ReviewTools} builds a
 * tool per reviewer, {@link CodeReviewCrewExample} builds an agent per reviewer, and the
 * offline script builds one answer per reviewer. Keeping the agent id, the tool id and the
 * display colour in one record is what stops those three from drifting apart: a colour that
 * belongs to the wrong reviewer, or a tool id that names a tool nobody registered, is a
 * change in this file only.
 *
 * <p>Immutable and thread-safe: records in an unmodifiable list, read-only after load.
 */
public final class Reviewers {

    /**
     * One specialist.
     *
     * @param agentId      the {@code AgentConfig.agentId()} — also the key the offline script
     *                     and the fan-out use to tell this reviewer from the others
     * @param shortName    the label on every console line
     * @param color        the ANSI colour its lines are painted in
     * @param toolId       the single tool this reviewer may call
     * @param systemPrompt its instructions
     * @param latencyMs    how long its tool pretends to work (see {@link ReviewTools})
     * @param finding      the finding its tool returns, reused verbatim by the offline script
     */
    public record Reviewer(
            String agentId,
            String shortName,
            String color,
            String toolId,
            String systemPrompt,
            long latencyMs,
            String finding) { }

    private static final String SECURITY_PROMPT =
            "You are a security reviewer. Read the file and report the single most serious "
            + "security issue you find, with a concrete fix. Answer in one or two sentences.";

    private static final String PERFORMANCE_PROMPT =
            "You are a performance reviewer. Read the file and report the single most serious "
            + "performance issue you find, with a concrete fix. Answer in one or two sentences.";

    private static final String STYLE_PROMPT =
            "You are a code-style reviewer. Read the file and report the most important "
            + "readability problem you find, with a concrete fix. Answer in one or two sentences.";

    private static final String SECURITY_FINDING =
            "SQL injection — the query is built by concatenating userId, so a crafted id can "
            + "rewrite the WHERE clause. Fix: a PreparedStatement with a bound parameter.";

    private static final String PERFORMANCE_FINDING =
            "O(n^2) — the nested loops compare every order against every other order, and "
            + "String += copies the whole result each pass. Fix: group by customerId in one pass "
            + "into a StringBuilder.";

    private static final String STYLE_FINDING =
            "Naming and constants — 'sql', 'result', 'i', 'j' are too terse, and ',' is a magic "
            + "literal. Fix: query/rows and a DELIMITER constant.";

    /** The three specialists, in the order they are declared, dispatched and reported. */
    public static final List<Reviewer> ALL = List.of(
            new Reviewer("security-reviewer", "security", Ansi.RED,
                    "scan_security", SECURITY_PROMPT, 180, SECURITY_FINDING),
            new Reviewer("performance-reviewer", "performance", Ansi.YELLOW,
                    "estimate_complexity", PERFORMANCE_PROMPT, 140, PERFORMANCE_FINDING),
            new Reviewer("style-reviewer", "style", Ansi.CYAN,
                    "check_style", STYLE_PROMPT, 100, STYLE_FINDING));

    /** The agent that merges the specialists' findings into a prioritised report. */
    public static final String LEAD_AGENT_ID = "review-lead";

    public static final String LEAD_PROMPT =
            "You are the review lead. Three specialists — security, performance and style — "
            + "have reviewed one file and you are given their findings. Merge them into a short "
            + "prioritised report: one line per issue, ordered by severity, each with its fix. "
            + "Lead with the highest-severity item.";

    private Reviewers() { }
}
