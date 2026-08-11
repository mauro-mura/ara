package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ReSpActStrategy}'s one behavioural divergence from {@link
 * ReactStrategy} (implicit natural-stop resolves to SPEAK, not FINAL_ANSWER), the
 * explicit SPEAK/FINAL_ANSWER sentinel parsing, the speak-callback delivery, tool-call
 * priority over both terminal branches, and the same suffix/native-tools/budget/trace
 * behavior {@code ReactStrategyTest} pins down for the plain ReAct loop (via the shared
 * {@link ReactExecutionSupport}, so those paths are exercised here mainly to confirm
 * the delegation actually happened, not to re-derive their logic).
 */
class ReSpActStrategyTest {

    private static AraTool noopTool() {
        return new AraTool() {
            @Override public String toolId()         { return "noop"; }
            @Override public String description()    { return "no-op"; }
            @Override public String argumentSchema()  { return "{}"; }
            @Override public ToolResult execute(String argsJson) { return ToolResult.success("noop", "ok"); }
        };
    }

    private static ToolRegistry namedRegistry(AraTool... tools) {
        var map = new HashMap<String, AraTool>();
        for (var t : tools) map.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) {
                return ids.isEmpty() ? List.copyOf(map.values())
                        : ids.stream().map(map::get).filter(Objects::nonNull).toList();
            }
            @Override public Optional<AraTool> findById(String id) {
                return Optional.ofNullable(map.get(id));
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                var t = map.get(toolId);
                return t != null ? t.execute(argumentJson) : ToolResult.failure(toolId, "unknown tool");
            }
        };
    }

    private static InMemoryMemoryManager seeded(String systemPrompt, String userInput) {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("system", systemPrompt);
        m.appendToWorkingMemory("user", userInput);
        return m;
    }

    private static AgentConfig config() {
        return AgentConfig.defaults().primaryLlm(LlmProfile.of("stub")).plannerStrategy("respact").build();
    }

    // ── Explicit sentinels ───────────────────────────────────────────────────────

    @Test
    void explicitSpeakSentinel_endsTheTurnAsSuccess_withASpeakTraceStep() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(new LlmCompletion("Action: SPEAK\nMessage: Which city do you mean?", 10, 10, "stop", null))
                .build();

        List<String> spoken = new ArrayList<>();
        AgentTask task = AgentTask.of("book a flight").withSpeakCallback(spoken::add);

        ExecutionResult result = new ReSpActStrategy().execute(
                task, llm, seeded("You are a booking assistant.", "book a flight"), ToolRegistry.empty(), config());

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals("Which city do you mean?", result.output());
        assertEquals(StepType.SPEAK, lastStep(result).type());
        assertEquals(List.of("Which city do you mean?"), spoken,
                "the speak callback must fire with the message text");
    }

    @Test
    void explicitFinalAnswerSentinel_stillEndsTheTaskAsFinalAnswer_notSpeak() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: Flight booked.", 10, 10, "stop", null))
                .build();

        List<String> spoken = new ArrayList<>();
        AgentTask task = AgentTask.of("book a flight").withSpeakCallback(spoken::add);

        ExecutionResult result = new ReSpActStrategy().execute(
                task, llm, seeded("You are a booking assistant.", "book a flight"), ToolRegistry.empty(), config());

        assertTrue(result.isSuccess());
        assertEquals("Flight booked.", result.output());
        assertEquals(StepType.FINAL_ANSWER, lastStep(result).type());
        assertTrue(spoken.isEmpty(), "a genuine final answer must not invoke the speak callback");
    }

    // ── The ReSpAct-specific divergence from ReactStrategy ────────────────────────

    @Test
    void naturalStopWithNoSentinelAndNoToolCall_resolvesToImplicitSpeak_notFinalAnswer() {
        // No "Action: SPEAK", no "FINAL_ANSWER" — just plain conversational text with a
        // natural stop. ReactStrategy would treat this as an implicit FINAL_ANSWER;
        // ReSpAct treats it as an implicit SPEAK — see the class Javadoc for why.
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(new LlmCompletion("Sure, I can help with that — one moment.", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReSpActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"), ToolRegistry.empty(), config());

        assertTrue(result.isSuccess());
        assertEquals("Sure, I can help with that — one moment.", result.output());
        assertEquals(StepType.SPEAK, lastStep(result).type(),
                "an unmarked natural stop must resolve to SPEAK for a conversational strategy");
    }

    @Test
    void nonStopFinishReason_withNoSentinel_doesNotResolveToAnything_andEventuallyExhaustsIterations() {
        // finishReason="length" (truncated) is not a natural stop and carries no sentinel
        // — must not be misread as an implicit speak/final-answer; the loop should keep
        // going (and fail once maxIterations is exhausted, since this stub never resolves).
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .fallback(new LlmCompletion("...still thinking...", 10, 10, "length", null))
                .build();

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub")).plannerStrategy("respact").maxIterations(3).build();

        ExecutionResult result = new ReSpActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"), ToolRegistry.empty(), config);

        assertFalse(result.isSuccess());
        assertTrue(result.failureReason().contains("Max iterations"));
    }

    // ── Tool-call priority ─────────────────────────────────────────────────────────

    @Test
    void toolCall_takesPriorityOverSpeakOrFinalAnswerTextInTheSameLoop() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("noop", "{}")
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReSpActStrategy().execute(
                AgentTask.of("do it"), llm, seeded("You are helpful.", "do it"), namedRegistry(noopTool()), config());

        assertTrue(result.isSuccess());
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.TOOL_CALL),
                "the tool call must have been dispatched before the final answer");
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.OBSERVATION));
        assertEquals(StepType.FINAL_ANSWER, lastStep(result).type());
    }

    @Test
    void speakThenToolCall_bothWorkAcrossIterationsInTheSameExecuteCall() {
        // Multiple Thought->Act rounds can happen before the loop terminates; SPEAK and
        // FINAL_ANSWER only differ in what they signal, not in whether the loop can keep
        // going beforehand — this exercises a tool round happening, then the turn ending
        // via SPEAK rather than FINAL_ANSWER.
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("noop", "{}")
                .then(new LlmCompletion("Action: SPEAK\nMessage: Found it — is that right?", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReSpActStrategy().execute(
                AgentTask.of("look it up"), llm, seeded("You are helpful.", "look it up"),
                namedRegistry(noopTool()), config());

        assertTrue(result.isSuccess());
        assertEquals("Found it — is that right?", result.output());
        List<StepType> order = result.steps().stream().map(ExecutionStep::type).toList();
        assertTrue(order.indexOf(StepType.TOOL_CALL) < order.indexOf(StepType.SPEAK),
                "the tool round must precede the speak turn: " + order);
    }

    // ── Suffix / native-tools (delegation to shared buildMessages logic) ───────────

    @Test
    void appendsToolCatalogAndRespactSuffix_whenClientLacksNativeTools() {
        RecordingLlmClient llm = new RecordingLlmClient(false);

        ExecutionResult result = new ReSpActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"), namedRegistry(noopTool()), config());

        assertTrue(result.isSuccess());
        assertTrue(llm.systemPrompt().contains("noop"));
        assertTrue(llm.systemPrompt().contains("Action: SPEAK"),
                "the RESPACT suffix teaching the SPEAK action must be appended for a non-native client");
    }

    @Test
    void omitsToolCatalogAndRespactSuffix_whenClientSupportsNativeTools() {
        RecordingLlmClient llm = new RecordingLlmClient(true);

        ExecutionResult result = new ReSpActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"), namedRegistry(noopTool()), config());

        assertTrue(result.isSuccess());
        assertFalse(llm.systemPrompt().contains("noop"));
        assertFalse(llm.systemPrompt().contains("Action: SPEAK"));
    }

    // ── Cancellation (shared with ReactStrategy via the same interrupt check) ──────

    @Test
    void cancelledBeforeFirstIteration_returnsCancelledFailure() {
        Thread.currentThread().interrupt();
        try {
            ScriptedLlmClient llm = ScriptedLlmClient.script().build();
            ExecutionResult result = new ReSpActStrategy().execute(
                    AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"), ToolRegistry.empty(), config());
            assertFalse(result.isSuccess());
            assertEquals("Cancelled", result.failureReason());
        } finally {
            Thread.interrupted();   // clear the flag so it doesn't leak into other tests
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static ExecutionStep lastStep(ExecutionResult result) {
        List<ExecutionStep> steps = result.steps();
        assertFalse(steps.isEmpty(), "expected at least one recorded step");
        return steps.get(steps.size() - 1);
    }

    /** Records the system prompt sent on the first LLM call and always returns FINAL_ANSWER. */
    private static final class RecordingLlmClient implements LlmClient {
        private final boolean nativeTools;
        private String systemPrompt;

        RecordingLlmClient(boolean nativeTools) { this.nativeTools = nativeTools; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            if (systemPrompt == null && !messages.isEmpty()) {
                systemPrompt = messages.get(0).content();
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: ok", 10, 10, "stop", null);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            throw new UnsupportedOperationException();
        }

        @Override public String  providerId()         { return "stub"; }
        @Override public boolean supportsNativeTools() { return nativeTools; }

        String systemPrompt() { return systemPrompt; }
    }
}
