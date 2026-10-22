package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.retriever.RetrievedChunk;
import io.ara.core.retriever.Retriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins how {@code RetrievalAugmentedStrategy} turns a retriever's answer into prompt
 * context: a chunk marked {@link RetrievedChunk#UNSCORED} shows no score, and a retriever
 * that fails at query time costs the context, not the task.
 */
class RetrievalAugmentedStrategyContextTest {

    /** Captures the messages of the last LLM call and always answers immediately. */
    private static final class CapturingLlmClient implements LlmClient {
        final AtomicReference<List<LlmMessage>> lastMessages = new AtomicReference<>();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) {
            lastMessages.set(messages);
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null);
        }

        @Override
        public String providerId() { return "capturing"; }

        String allContent() {
            StringBuilder sb = new StringBuilder();
            lastMessages.get().forEach(m -> sb.append(m.content()).append('\n'));
            return sb.toString();
        }
    }

    private static AgentResponse runWith(Retriever retriever, CapturingLlmClient llm) {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .retriever(retriever)
                .build();
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("rag-agent"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("rag+react")
                .build());
        return agent.execute(AgentTask.of("question"));
    }

    @Test
    void scoredChunk_showsItsScore() {
        CapturingLlmClient llm = new CapturingLlmClient();

        AgentResponse resp = runWith((q, n) -> List.of(
                new RetrievedChunk("d1", "Scored", "scored-content", 0.87)), llm);

        assertTrue(resp.isSuccess());
        String prompt = llm.allContent();
        assertTrue(prompt.contains("Source: Scored (score=0.87)"), prompt);
    }

    @Test
    void unscoredChunk_showsNoScore() {
        CapturingLlmClient llm = new CapturingLlmClient();

        AgentResponse resp = runWith((q, n) -> List.of(
                new RetrievedChunk("d1", "First", "first-content", RetrievedChunk.UNSCORED),
                new RetrievedChunk("d2", "Second", "second-content", RetrievedChunk.UNSCORED)), llm);

        assertTrue(resp.isSuccess());
        String prompt = llm.allContent();
        assertTrue(prompt.contains("[1] Source: First\nfirst-content"), prompt);
        assertTrue(prompt.contains("[2] Source: Second\nsecond-content"), prompt);
        assertFalse(prompt.contains("score="), prompt);
        assertFalse(prompt.contains("NaN"), prompt);
    }

    @Test
    void failingRetriever_taskRunsWithoutContext() {
        CapturingLlmClient llm = new CapturingLlmClient();

        AgentResponse resp = runWith((q, n) -> {
            throw new IllegalStateException("index unavailable");
        }, llm);

        assertTrue(resp.isSuccess(), "a retrieval failure must not fail the task");
        assertNotNull(llm.lastMessages.get(), "the delegate strategy must still call the LLM");
        assertFalse(llm.allContent().contains("Retrieved Context"),
                "no context block when retrieval failed");
    }

    @Test
    void hasScore_distinguishesUnscored() {
        assertTrue(new RetrievedChunk("d", "t", "c", 0.0).hasScore());
        assertFalse(new RetrievedChunk("d", "t", "c", RetrievedChunk.UNSCORED).hasScore());
    }
}
