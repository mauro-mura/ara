package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0087: {@code AraRuntime.Builder.tokenCounter(...)} reaches the default
 * {@code SlidingWindowMemoryManager} and is actually consulted for eviction decisions,
 * instead of the historic {@code chars / 4} estimate. Proven the same way ADR-0086's own
 * wiring test proves eviction runs at all ({@code AraRuntimeMemoryWiringTest}): by comparing
 * observable window size after the same turns, not by reaching into private state.
 */
class AraRuntimeTokenCounterWiringTest {

    private static final String PADDING = " padding padding padding padding padding";

    /** Records the message list of the most recent {@code complete} call. */
    private static final class RecordingLlmClient implements LlmClient {
        final AtomicReference<List<LlmMessage>> lastMessages = new AtomicReference<>(List.of());
        private int turn = 0;

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            lastMessages.set(List.copyOf(messages));
            turn++;
            return new LlmCompletion(
                    "Action: FINAL_ANSWER\nAnswer: reply " + turn + PADDING, 10, 10, "stop", null);
        }

        @Override
        public String providerId() { return "recording"; }
    }

    /**
     * Deliberately far coarser than {@code chars / 4}: 50 "tokens" per whitespace-separated
     * word. Under the same numeric budget this forces eviction to trigger on far fewer
     * entries than the char-based approximation would — the signal this test checks for.
     */
    private static final class HeavyWordTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            if (text == null || text.isBlank()) return 0;
            return text.trim().split("\\s+").length * 50;
        }
    }

    private static AgentConfig.Builder baseConfig(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))
                .plannerStrategy("react")
                .maxIterations(3)
                .maxConversationTurns(20)
                .workingMemoryTokenBudget(200)
                .workingMemoryEviction("drop_oldest");
    }

    /** Runs 10 turns on {@code runtime} and returns the final message count sent to the LLM. */
    private static int runTurnsAndCountFinalWindow(AraRuntime runtime, RecordingLlmClient llm, AgentId id) {
        AraAgent agent = runtime.createAgent(baseConfig(id).build());
        SessionId session = SessionId.of("s1");
        for (int i = 0; i < 10; i++) {
            AgentResponse r = agent.execute(AgentTask.of("message " + i + PADDING).withSessionId(session));
            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
        }
        return llm.lastMessages.get().size();
    }

    @Test
    void customTokenCounter_drivesEvictionInsteadOfCharsApproximation() {
        AgentId withoutId = AgentId.of("no-counter-agent");
        RecordingLlmClient llmWithout = new RecordingLlmClient();
        AraRuntime runtimeWithout = AraRuntime.builder().llmClient(withoutId.value(), llmWithout).build();
        int sentWithoutCounter = runTurnsAndCountFinalWindow(runtimeWithout, llmWithout, withoutId);

        AgentId withId = AgentId.of("heavy-counter-agent");
        RecordingLlmClient llmWith = new RecordingLlmClient();
        AraRuntime runtimeWith = AraRuntime.builder()
                .llmClient(withId.value(), llmWith)
                .tokenCounter(new HeavyWordTokenCounter())
                .build();
        int sentWithCounter = runTurnsAndCountFinalWindow(runtimeWith, llmWith, withId);

        assertTrue(sentWithCounter < sentWithoutCounter,
                () -> "a token counter weighing words far above chars/4 must evict more "
                        + "aggressively under the same numeric budget — got " + sentWithCounter
                        + " (with) vs " + sentWithoutCounter + " (without)");
    }
}
