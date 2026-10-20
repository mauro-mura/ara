package io.ara.examples.background;

import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import io.ara.examples.support.Ansi;
import io.ara.examples.support.Live;
import io.ara.examples.support.Tools;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Background delegation</b>: an agent starts two slow sub-tasks, carries on with work of its
 * own while they run, and gets each result back by itself when it is ready — instead of
 * standing still until the other agent answers, which is what {@code delegate_task} does by
 * default.
 *
 * <p>The story, with the clock on every line. A coordinator must report a total made of three
 * numbers: two laboratory measurements (lab A takes 1.2 s, lab B takes 3.2 s) and a value it
 * finds by reading a chain of three notes itself (0.8 s each, and each note says which one to
 * read next, so they can only be read one after the other). Done one thing at a time that is
 * 6.8 s. Done together it is about as long as the slowest lab, because the notes are read
 * while the labs measure.
 *
 * <p>What to watch for in the output:
 * <ol>
 *   <li>The two {@code delegate_task} calls return at once with a task id: nothing waits.</li>
 *   <li>While the coordinator reads notes, lab A finishes. Its result is not asked for: it is put
 *       in front of the model, marked as automated, at the start of its next turn.</li>
 *   <li>The coordinator then gives an answer while lab B is still measuring. The run does not
 *       end there: it waits for B, hands over the result, and lets the model answer once more
 *       with it. A run never ends leaving work it started running behind it.</li>
 * </ol>
 *
 * <p>How it is switched on: {@code AgentConfig.asyncDelegation(AsyncDelegation.defaults())} on the
 * agent that delegates. Without it {@code delegate_task} is exactly the waiting call it always
 * was, with no extra parameter in its schema. The labs are ordinary agents here (deterministic
 * ones, because a lab has no business reasoning); any agent can be started this way.
 *
 * <p>Runs offline by default: the coordinator's model is a script, so the sequence is the same
 * every time and the example checks its own claims at the end. Pass {@code live} as the first
 * argument (or {@code -Dara.example.live=true}) to give the same job to a real OpenAI-compatible
 * model. Be warned that a small model may wait and ask "is it done yet?" over and over instead
 * of working, which costs a whole model turn per question; the claims are not checked then,
 * and the example just shows what happened.
 *
 * @see io.ara.examples.events.RunEventsExample — every step of a run, including a delegate's, as it happens
 */
public final class BackgroundDelegationExample {

    private static final String LIVE_BASE_URL = "http://127.0.0.1:1234/v1";
    private static final String LIVE_MODEL    = "qwen3-coder-30b-a3b-instruct";
    /** LM Studio ignores the key but langchain4j wants a non-blank string. */
    private static final String LIVE_API_KEY  = Live.apiKey("not-required");

    private static final long LAB_A_MS = 1_200;
    private static final long LAB_B_MS = 3_200;
    private static final long NOTE_MS  = 800;
    /** What doing the same three things one after the other would cost. */
    private static final double ONE_AT_A_TIME_SECONDS = (LAB_A_MS + LAB_B_MS + 3 * NOTE_MS) / 1000.0;

    private static final String INSTRUCTIONS = """
            You coordinate a measurement job. Report one line, TOTAL=<n>, the sum of three numbers: the \
            measurement of sample A (from agent lab-a), the measurement of sample B (from agent lab-b), and the \
            VALUE found by reading notes with read_note, starting at id "start"; every note names the next one \
            to read, until a note contains VALUE=<number>. Start the two measurements in the background \
            (delegate_task with wait false and the labels A and B), then read the notes yourself while they \
            run. Their results will be delivered to you. Never guess a number.""";

    private static final Instant START = Instant.now();

    public static void main(String[] args) {
        boolean live = Live.requested(args);
        banner(live);

        NarratingModel coordinatorModel = new NarratingModel(live ? openAi() : offlineScript());
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", coordinatorModel)
                .toolRegistry(Tools.registry(new ReadNote()))
                .build()) {

            runtime.registry().register(lab("lab-a", "A", 17, LAB_A_MS));
            runtime.registry().register(lab("lab-b", "B", 25, LAB_B_MS));
            AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                    .agentId(AgentId.of("coordinator")).agentType("coordinator")
                    .systemPrompt(INSTRUCTIONS)
                    .primaryLlm(LlmProfile.of("coordinator-model"))
                    .plannerStrategy("react")
                    .enabledTools(List.of("delegate_task", "read_note"))
                    // The one line that turns it on. Default: 4 tasks at once, results cut at 8000 characters.
                    .asyncDelegation(AsyncDelegation.defaults())
                    .maxIterations(live ? 14 : 10)
                    .executionTimeout(Duration.ofMinutes(live ? 5 : 1))
                    .build());

            say("you", "Report the total of samples A and B and the value in the notes.");
            AgentResponse response = coordinator.execute(AgentTask.of(
                    "Report the total of samples A and B and the value in the notes."));

            double seconds = Duration.between(START, Instant.now()).toMillis() / 1000.0;
            summary(response, seconds);
            if (!live) {
                checkClaims(coordinatorModel, response, seconds);
            }
        }
    }

    // ── the cast ──────────────────────────────────────────────────────────────

    /** A laboratory: takes {@code millis} to "measure", then answers with the number. No model involved. */
    private static AraAgent lab(String id, String sample, int value, long millis) {
        return AraAgents.deterministic(AgentId.of(id), task -> {
            say(id, "measuring sample " + sample + " (takes " + seconds(millis) + ")...");
            sleep(millis);
            say(id, "done: sample " + sample + " = " + value);
            return "Sample " + sample + " = " + value;
        });
    }

    /** The chain of notes the coordinator reads itself. Each read takes {@link #NOTE_MS}. */
    private static final class ReadNote implements AraTool {
        @Override public String toolId() { return "read_note"; }
        @Override public String description() { return "Reads one note. Argument: id of the note, starting with \"start\"."; }
        @Override public String argumentSchema() {
            return "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}";
        }

        @Override
        public ToolResult execute(String argumentJson) {
            String id = argumentJson.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]*)\".*", "$1");
            sleep(NOTE_MS);
            String note = switch (id) {
                case "start" -> "Note start: the next note to read is k7.";
                case "k7"    -> "Note k7: the next note to read is m3.";
                case "m3"    -> "Note m3: VALUE=9";
                default      -> "No such note: " + id;
            };
            say("coordinator", "read note \"" + id + "\" -> " + note.substring(note.indexOf(':') + 2));
            return ToolResult.success(toolId(), note);
        }
    }

    /**
     * Seven model turns, always the same. The two labs start in the background, three notes are
     * read, the model answers (lab B is still running), the run waits for B and hands its result
     * over, and the model answers again.
     */
    private static LlmClient offlineScript() {
        return ScriptedLlmClient.script()
                .thenToolCall("delegate_task", "{\"agent_id\":\"lab-a\",\"task\":\"Measure sample A\",\"wait\":false,\"label\":\"A\"}")
                .thenToolCall("delegate_task", "{\"agent_id\":\"lab-b\",\"task\":\"Measure sample B\",\"wait\":false,\"label\":\"B\"}")
                .thenToolCall("read_note", "{\"id\":\"start\"}")
                .thenToolCall("read_note", "{\"id\":\"k7\"}")
                .thenToolCall("read_note", "{\"id\":\"m3\"}")
                .thenFinalAnswer("I have A and the value in the notes; sample B is still being measured.")
                .thenFinalAnswer("TOTAL=51   (A 17 + B 25 + notes 9)")
                .build();
    }

    private static LlmClient openAi() {
        return OpenAiLlmClient.builder().baseUrl(LIVE_BASE_URL).apiKey(LIVE_API_KEY).modelName(LIVE_MODEL).build();
    }

    // ── watching the model ────────────────────────────────────────────────────

    /**
     * Wraps the coordinator's model to narrate each turn and to remember what each turn was shown,
     * which is how the example can say — and later check — in which turn a result arrived.
     */
    private static final class NarratingModel implements LlmClient {
        private final LlmClient inner;
        final List<List<LlmMessage>> shown = new CopyOnWriteArrayList<>();
        private int reported;   // results already announced, so each is announced once

        NarratingModel(LlmClient inner) { this.inner = inner; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            shown.add(List.copyOf(messages));
            int turn = shown.size();
            List<String> arrived = newResults(messages);
            say("runtime", arrived.isEmpty() ? "model turn " + turn
                    : "model turn " + turn + " — put in front of the model, unasked: " + String.join("; ", arrived));
            LlmCompletion completion = inner.complete(messages, context);
            say("model", describe(completion));
            return completion;
        }

        /** What the model did, in one line: the tool it called, or the answer it gave. */
        private static String describe(LlmCompletion completion) {
            if (!completion.toolCalls().isEmpty()) {
                var call = completion.toolCalls().get(0);
                return "calls " + call.toolId() + " " + call.argumentJson();
            }
            if (completion.toolCallJson() != null) {
                return "calls " + completion.toolCallJson().replaceAll("\\s+", " ");
            }
            // The text protocol the scripted and the small models answer in wraps the answer in a label.
            return "answers: " + completion.text().replaceFirst("(?s)^\\s*Action:\\s*FINAL_ANSWER\\s*Answer:\\s*", "")
                    .trim().replace("\n", " ");
        }

        /** The results the runtime put in front of the model, as messages marked "automated", that were not there last turn. */
        private List<String> newResults(List<LlmMessage> messages) {
            List<String> all = messages.stream().map(LlmMessage::content)
                    .filter(text -> text != null && text.startsWith("[automated — background task result"))
                    .map(text -> text.substring(text.indexOf('\n') + 1).replace("\n", " ").replaceFirst("^task \\S+ ", "task "))
                    .toList();
            List<String> fresh = all.subList(Math.min(reported, all.size()), all.size());
            reported = all.size();
            return List.copyOf(fresh);
        }

        @Override public String providerId() { return inner.providerId(); }
        @Override public boolean supportsNativeTools() { return inner.supportsNativeTools(); }

        boolean sawInTurn(int turn, String text) {
            return shown.get(turn - 1).stream().anyMatch(message -> message.content() != null && message.content().contains(text));
        }
    }

    // ── the ending ────────────────────────────────────────────────────────────

    private static void summary(AgentResponse response, double seconds) {
        System.out.println();
        say("coordinator", "answer: " + response.content().trim().replace("\n", " "));
        System.out.printf(Locale.ROOT, "%nFinished in %.1f s. Doing the same three things one after the other takes about %.1f s "
                + "(%.1f + %.1f for the labs, 3 x %.1f for the notes).%n", seconds, ONE_AT_A_TIME_SECONDS,
                LAB_A_MS / 1000.0, LAB_B_MS / 1000.0, NOTE_MS / 1000.0);
    }

    /** The claims in this example's header, checked against what actually happened. */
    private static void checkClaims(NarratingModel model, AgentResponse response, double seconds) {
        check(response.isSuccess(), "the run succeeded");
        check(model.shown.size() == 7, "the model took seven turns (the second answer exists only because the run waited), took " + model.shown.size());
        check(!model.sawInTurn(4, "Sample A = 17"), "lab A's result had not arrived yet at turn 4");
        check(model.sawInTurn(5, "Sample A = 17"), "lab A's result reached the model by itself at turn 5, while it was reading notes");
        check(!model.sawInTurn(6, "Sample B = 25"), "lab B had not finished when the model first answered");
        check(model.sawInTurn(7, "Sample B = 25"), "the run waited for lab B and handed its result to the model");
        check(response.content().contains("TOTAL=51"), "the final answer carries the total");
        check(seconds < ONE_AT_A_TIME_SECONDS - 1.0, "it took less than doing the three things one at a time, took " + seconds + " s");
        System.out.println(Ansi.paint(Ansi.GREEN, "\nAll claims hold."));
    }

    private static void check(boolean claim, String what) {
        if (!claim) {
            throw new IllegalStateException("Claim failed: " + what);
        }
        System.out.println(Ansi.paint(Ansi.GREEN, "  ok  ") + what);
    }

    private static void banner(boolean live) {
        System.out.println(Ansi.paint(Ansi.BOLD, "Background delegation") + " — "
                + (live ? "a real model (" + LIVE_MODEL + ")" : "scripted model, offline"));
        System.out.println(Ansi.paint(Ansi.DIM, "Two slow labs run in the background while the coordinator reads notes. "
                + "Times are from the start; \"runtime\" lines are what ARA does around the model.\n"));
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private static void say(String who, String text) {
        double seconds = Duration.between(START, Instant.now()).toMillis() / 1000.0;
        System.out.println(Ansi.paint(Ansi.DIM, String.format(Locale.ROOT, "[%4.1fs] ", seconds))
                + Ansi.paint(Ansi.CYAN, String.format("%-11s ", who)) + text);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BackgroundDelegationExample() { }
}
