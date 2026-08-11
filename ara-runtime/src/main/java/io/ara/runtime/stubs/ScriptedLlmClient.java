package io.ara.runtime.stubs;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test/stub {@link LlmClient} that replays a pre-defined sequence of completions.
 *
 * <p>Each call to {@link #complete} pops the next {@link LlmCompletion} from the
 * script. When the script is exhausted, the {@code fallback} completion is returned
 * on every subsequent call (defaults to an empty FINAL_ANSWER so the ReAct loop
 * terminates cleanly rather than hanging).
 *
 * <p>Use the fluent {@link Builder} to compose the script:
 * <pre>{@code
 * LlmClient stub = ScriptedLlmClient.script()
 *     .thenToolCall("delegate_task", """{"agent_id":"writer-0","task":"Scrivi intro"}""")
 *     .thenFinalAnswer("Il writer ha risposto: vedi osservazione.")
 *     .build();
 * }</pre>
 */
public final class ScriptedLlmClient implements LlmClient {

    private final Queue<LlmCompletion> script;
    private final LlmCompletion        fallback;

    private ScriptedLlmClient(List<LlmCompletion> steps, LlmCompletion fallback) {
        this.script   = new ConcurrentLinkedQueue<>(steps);
        this.fallback = fallback;
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
        LlmCompletion next = script.poll();
        return next != null ? next : fallback;
    }

    @Override
    public String providerId() {
        return "scripted-stub";
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Returns a new builder for composing the response script. */
    public static Builder script() {
        return new Builder();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private final List<LlmCompletion> steps    = new ArrayList<>();
        private LlmCompletion             fallback = finalAnswerCompletion("(no more scripted responses)");

        private Builder() {}

        /**
         * Adds a step that instructs the ReAct loop to invoke a tool.
         *
         * @param toolId       the tool identifier (e.g. {@code "delegate_task"})
         * @param argumentJson JSON arguments in ARA format: {@code {"agent_id":"...","task":"..."}}
         */
        public Builder thenToolCall(String toolId, String argumentJson) {
            String toolCallJson = """
                    {"tool_id":"%s","arguments":%s}""".formatted(toolId, argumentJson);
            steps.add(new LlmCompletion(
                    "I need to call tool: " + toolId,
                    10, 10, "tool_calls", toolCallJson));
            return this;
        }

        /**
         * Adds a step that terminates the ReAct loop with a final answer.
         *
         * @param answer the answer text returned to the caller
         */
        public Builder thenFinalAnswer(String answer) {
            steps.add(finalAnswerCompletion(answer));
            return this;
        }

        /**
         * Adds a raw {@link LlmCompletion} step for full control.
         */
        public Builder then(LlmCompletion completion) {
            steps.add(completion);
            return this;
        }

        /**
         * Sets the completion returned when the script is exhausted.
         * Defaults to a generic FINAL_ANSWER so the loop terminates cleanly.
         */
        public Builder fallback(LlmCompletion completion) {
            this.fallback = completion;
            return this;
        }

        public ScriptedLlmClient build() {
            return new ScriptedLlmClient(steps, fallback);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LlmCompletion finalAnswerCompletion(String answer) {
        return new LlmCompletion(
                "Action: FINAL_ANSWER\nAnswer: " + answer,
                10, 20, "stop", null);
    }
}