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
 * Verifies multi-retriever registration on {@code AraRuntime.Builder} (mirrors the
 * multi-{@code LlmClient} pattern, ADR-030): agents select a retriever by id via
 * {@code AgentConfig.retrieverId()}, resolved per task through {@code RetrieverRouter}
 * — a single registered {@code "rag+react"} strategy instance serves every agent,
 * regardless of how many retrievers are declared on the runtime.
 */
class AraRuntimeMultiRetrieverTest {

    private static Retriever fixed(String tag) {
        return (query, maxResults) -> List.of(new RetrievedChunk("doc-" + tag, tag, "content-from-" + tag, 0.9));
    }

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
    }

    @Test
    void agentSelectsItsOwnRetriever_byId() {
        CapturingLlmClient llmA = new CapturingLlmClient();
        CapturingLlmClient llmB = new CapturingLlmClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("llm-a", llmA)
                .llmClient("llm-b", llmB)
                .retriever("kb-a", fixed("A"))
                .retriever("kb-b", fixed("B"))
                .build();

        AraAgent agentA = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("agent-a"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("llm-a"))
                .plannerStrategy("rag+react")
                .retrieverId("kb-a")
                .build());

        AraAgent agentB = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("agent-b"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("llm-b"))
                .plannerStrategy("rag+react")
                .retrieverId("kb-b")
                .build());

        AgentResponse respA = agentA.execute(AgentTask.of("question"));
        AgentResponse respB = agentB.execute(AgentTask.of("question"));

        assertTrue(respA.isSuccess());
        assertTrue(respB.isSuccess());

        String systemMsgA = llmA.lastMessages.get().get(0).content();
        String systemMsgB = llmB.lastMessages.get().get(0).content();

        assertTrue(systemMsgA.contains("content-from-A"), "agent-a should see kb-a's context");
        assertFalse(systemMsgA.contains("content-from-B"), "agent-a must not see kb-b's context");
        assertTrue(systemMsgB.contains("content-from-B"), "agent-b should see kb-b's context");
        assertFalse(systemMsgB.contains("content-from-A"), "agent-b must not see kb-a's context");
    }

    @Test
    void agentWithoutRetrieverId_usesFirstRegisteredAsDefault() {
        CapturingLlmClient llm = new CapturingLlmClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .retriever("kb-a", fixed("A"))   // first registered -> becomes the default
                .retriever("kb-b", fixed("B"))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("agent-default"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("rag+react")
                // no retrieverId() set -> falls back to the router's default
                .build());

        AgentResponse resp = agent.execute(AgentTask.of("question"));
        assertTrue(resp.isSuccess());
        assertTrue(llm.lastMessages.get().get(0).content().contains("content-from-A"));
    }

    @Test
    void singleRetrieverOverload_stillWorks_backwardCompatible() {
        CapturingLlmClient llm = new CapturingLlmClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .retriever(fixed("SINGLE"))   // legacy single-retriever overload
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("agent-legacy"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("rag+react")
                .build());

        AgentResponse resp = agent.execute(AgentTask.of("question"));
        assertTrue(resp.isSuccess());
        assertTrue(llm.lastMessages.get().get(0).content().contains("content-from-SINGLE"));
    }

    @Test
    void retrieverId_withoutRagStrategy_failsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                AgentConfig.defaults()
                        .agentId(AgentId.of("bad-agent"))
                        .agentType("t")
                        .primaryLlm(LlmProfile.of("default"))
                        .plannerStrategy("react")   // plain react, not rag+...
                        .retrieverId("kb-a")
                        .build());

        assertTrue(ex.getMessage().contains("retrieverId"));
        assertTrue(ex.getMessage().contains("react"));
    }

    @Test
    void retrieverId_withRagStrategy_isAccepted() {
        assertDoesNotThrow(() ->
                AgentConfig.defaults()
                        .agentId(AgentId.of("good-agent"))
                        .agentType("t")
                        .primaryLlm(LlmProfile.of("default"))
                        .plannerStrategy("rag+react")
                        .retrieverId("kb-a")
                        .build());
    }
}
