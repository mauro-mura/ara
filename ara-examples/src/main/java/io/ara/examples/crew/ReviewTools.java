package io.ara.examples.crew;

import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import io.ara.examples.crew.Reviewers.Reviewer;
import io.ara.examples.support.Ansi;

import java.util.List;

/**
 * The three specialist tools the review crew calls — one per reviewer, described by
 * {@link Reviewers} — plus the Java file they review. Everything is deterministic and
 * offline: the "analysis" is a canned finding with a simulated latency, so the demo needs
 * no model, no network and no real static analysis, and it produces the same output on
 * every machine.
 *
 * <p>The latency is not decoration. {@link CodeReviewCrewExample} runs the three reviewers
 * concurrently, and the point is that a reader can <em>see</em> it: each tool prints a start
 * line and a done line, and with 100-180ms of simulated work per reviewer the three start
 * lines land together and the done lines come back in an order decided by the work, not by
 * submission. A zero-latency stub would print everything in a tight serial burst and prove
 * nothing about the fan-out.
 *
 * <p>Thread-safety: each tool is stateless and safe to call from any thread. The single
 * shared mutable-looking field, {@link #CLOCK_ORIGIN}, is written once at class load and
 * only read afterwards, so it is effectively a constant — it exists so every line reports
 * milliseconds since the crew started rather than an absolute timestamp.
 */
public final class ReviewTools {

    /** The file under review — named in the task and in every tool description. */
    public static final String FILE_NAME = "OrderService.java";

    /**
     * The code the crew reviews. Deliberately small and deliberately flawed: a concatenated
     * SQL query (security), a nested loop that rebuilds a String (performance) and terse
     * names with a magic delimiter (style) — one obvious finding per reviewer, so the three
     * answers do not overlap and the lead has something real to prioritise.
     */
    public static final String SOURCE = """
            public class OrderService {

                public String findOrders(String userId, List<Order> orders) {
                    String sql = "SELECT * FROM orders WHERE user_id = '" + userId + "'";
                    String result = "";
                    for (int i = 0; i < orders.size(); i++) {
                        for (int j = 0; j < orders.size(); j++) {
                            if (orders.get(i).getCustomerId().equals(orders.get(j).getCustomerId())) {
                                result += orders.get(i).getId() + ",";
                            }
                        }
                    }
                    return sql + result;
                }
            }""";

    /** Milliseconds-since-crew-start baseline; written once at class load, read-only after. */
    private static final long CLOCK_ORIGIN = System.nanoTime();

    /** One tool per reviewer, in reviewer order. */
    public static List<AraTool> all() {
        return Reviewers.ALL.stream().map(ReviewTools::finding).toList();
    }

    /**
     * Builds one specialist tool: it logs a start line, sleeps the reviewer's latency, logs a
     * done line carrying the finding, and returns the finding as its observation. The
     * reviewer's colour and short name travel in the tool so every line it prints is
     * attributable to the reviewer that owns it — the tool itself has no access to the
     * calling agent's identity.
     */
    private static AraTool finding(Reviewer reviewer) {
        return new AraTool() {
            @Override public String toolId() { return reviewer.toolId(); }

            @Override public String description() {
                return "Returns the " + reviewer.shortName() + " findings for " + FILE_NAME + ".";
            }

            @Override public String argumentSchema() {
                return """
                        {"type":"object","properties":{"file":{"type":"string"}},"required":["file"]}""";
            }

            @Override
            public ToolResult execute(String argumentJson) {
                report(reviewer, "▸", reviewer.toolId() + "  · reading " + FILE_NAME,
                        millisSinceOrigin());
                sleep(reviewer.latencyMs());
                report(reviewer, "✓", reviewer.toolId() + "  · 1 finding", millisSinceOrigin());
                return ToolResult.success(reviewer.toolId(), reviewer.finding());
            }
        };
    }

    // ── Presentation ──────────────────────────────────────────────────────────

    /**
     * Prints one reviewer line: a fixed-width coloured tag, a marker, the message and a dim
     * timing/thread suffix. The thread id is the concrete evidence of concurrency — three
     * different ids on the start lines mean three threads, which on this runtime are virtual.
     */
    private static void report(Reviewer reviewer, String marker, String message, long ms) {
        String tag = String.format("%-22s", "[" + reviewer.shortName() + "]");
        System.out.printf("%s %s %s%s%n",
                Ansi.paint(reviewer.color(), tag),
                Ansi.paint(reviewer.color(), marker),
                message,
                Ansi.paint(Ansi.GREY, "  +" + ms + "ms  tid=" + Thread.currentThread().threadId()));
    }

    private static long millisSinceOrigin() {
        return (System.nanoTime() - CLOCK_ORIGIN) / 1_000_000;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ReviewTools() { }
}
