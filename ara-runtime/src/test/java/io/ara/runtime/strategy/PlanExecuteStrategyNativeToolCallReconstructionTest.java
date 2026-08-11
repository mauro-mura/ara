package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link PlanExecuteStrategy}'s step-execution loop reconstructs a native
 * tool-call turn ({@code "assistant_tool_call"} + {@code "tool"} roles, carrying
 * {@code toolCallId}/{@code toolName}) on the round following a native tool call, instead of
 * the plain-text {@code "assistant"}/{@code "user"} fallback — mirroring {@code
 * ReactStrategy}'s dispatch, and pairing with {@code ToolConversionUtils.toNativeAwareChatMessage}
 * in the adapters so the reconstructed history is actually usable end to end.
 */
class PlanExecuteStrategyNativeToolCallReconstructionTest {

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

    private static InMemoryMemoryManager seeded() {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("system", "You are helpful.");
        m.appendToWorkingMemory("user", "do the thing");
        return m;
    }

    @Test
    void nativeClient_toolCallRound_reconstructedAsNativeRolesOnNextRound() {
        StepToolCallLlmClient llm = new StepToolCallLlmClient(true);

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("plan_execute")
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("do the thing"), llm, seeded(), namedRegistry(noopTool()), config);

        assertTrue(result.isSuccess(), result.failureReason());

        List<LlmMessage> round2 = llm.stepCallMessages(1);   // messages sent on the round after the tool call

        LlmMessage assistantToolCall = round2.stream()
                .filter(m -> "assistant_tool_call".equals(m.role()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an assistant_tool_call message"));
        assertEquals("call-99", assistantToolCall.toolCallId());
        assertEquals("noop", assistantToolCall.toolName());

        LlmMessage toolResult = round2.stream()
                .filter(m -> "tool".equals(m.role()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a tool message"));
        assertEquals("call-99", toolResult.toolCallId());
        assertEquals("noop", toolResult.toolName());
        assertEquals("ok", toolResult.content());

        assertTrue(round2.stream().noneMatch(m -> "user".equals(m.role())
                        && m.content() != null && m.content().startsWith("Observation:")),
                "the plain-text Observation: fallback must not be used for a native tool call");
    }

    @Test
    void nonNativeClient_toolCallRound_usesPlainTextFallbackOnNextRound() {
        StepToolCallLlmClient llm = new StepToolCallLlmClient(false);

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("plan_execute")
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("do the thing"), llm, seeded(), namedRegistry(noopTool()), config);

        assertTrue(result.isSuccess(), result.failureReason());

        List<LlmMessage> round2 = llm.stepCallMessages(1);

        assertTrue(round2.stream().noneMatch(m -> "assistant_tool_call".equals(m.role())),
                "a non-native completion never carries a toolCallId, so no native reconstruction is possible");
        assertTrue(round2.stream().anyMatch(m -> "user".equals(m.role())
                        && m.content() != null && m.content().startsWith("Observation:")),
                "the plain-text Observation: fallback should still be used");
    }

    // ── Stub ───────────────────────────────────────────────────────────────────

    /**
     * Scripted for exactly the flow this test drives: one plan step whose first
     * step-execution round is a tool call — native only if {@code nativeCompletion} is
     * {@code true}, mirroring a real provider (a non-native client's completions never carry a
     * {@code toolCallId}) — whose second round is {@code STEP_DONE}, then a synthesis call.
     */
    private static final class StepToolCallLlmClient implements LlmClient {
        private final boolean nativeCompletion;
        private final List<List<LlmMessage>> stepCalls = new ArrayList<>();
        private final AtomicInteger stepCallCount = new AtomicInteger(0);

        StepToolCallLlmClient(boolean nativeCompletion) { this.nativeCompletion = nativeCompletion; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            String system = messages.get(0).content();
            if (system.contains("Produce a short numbered execution plan")) {
                return new LlmCompletion("1. Step one", 5, 5, "stop", null);
            }
            if (system.contains("You are executing one step of a plan")) {
                stepCalls.add(List.copyOf(messages));
                int round = stepCallCount.getAndIncrement();
                if (round == 0) {
                    return nativeCompletion
                            ? new LlmCompletion("calling noop", 5, 5, "tool_calls",
                                    "{\"tool_id\":\"noop\",\"arguments\":{}}", "call-99")
                            : new LlmCompletion("{\"tool_id\":\"noop\",\"arguments\":{}}", 5, 5, "stop", null);
                }
                return new LlmCompletion("STEP_DONE", 5, 5, "stop", null);
            }
            return new LlmCompletion("Final answer.", 5, 5, "stop", null);
        }

        @Override
        public String providerId() { return "step-tool-call-test"; }

        @Override
        public boolean supportsNativeTools() { return nativeCompletion; }

        List<LlmMessage> stepCallMessages(int roundIndex) { return stepCalls.get(roundIndex); }
    }
}
