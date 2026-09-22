package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmRouter;
import io.ara.core.retriever.RetrievedChunk;
import io.ara.core.retriever.RetrieverRouter;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition seams added to {@code AraRuntime.Builder} for callers that need to
 * override routing rather than only register participants: {@code retrieverRouter(...)}
 * and {@code reflectionRouter(...)}.
 */
class AraRuntimeSeamsTest {

    private static RetrieverRouter retrieverRouterTagging(String tag) {
        return id -> (query, maxResults) ->
                List.of(new RetrievedChunk("doc", tag, "ctx-from-" + tag, 0.9));
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
    void retrieverRouter_injectsCustomRouter_andRegistersRagStrategies() {
        CapturingLlmClient llm = new CapturingLlmClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .retrieverRouter(retrieverRouterTagging("CUSTOM"))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("agent-custom-rag"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("rag+react")
                .build());

        AgentResponse resp = agent.execute(AgentTask.of("question"));

        assertTrue(resp.isSuccess());
        assertTrue(llm.lastMessages.get().get(0).content().contains("ctx-from-CUSTOM"),
                "the custom router must be the one consulted for RAG context");
    }

    @Test
    void retrieverRouter_conflictsWithNamedRetrievers() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                AraRuntime.builder()
                        .llmClient(new CapturingLlmClient())
                        .retriever((query, maxResults) -> List.of())
                        .retrieverRouter(retrieverRouterTagging("CUSTOM"))
                        .build());

        assertTrue(ex.getMessage().contains("retrieverRouter"), ex.getMessage());
    }

    @Test
    void reflectionRouter_isUsedForReflectionCalls() {
        AtomicInteger selects = new AtomicInteger();
        LlmClient reflectionClient = ScriptedLlmClient.script()
                .thenFinalAnswer("critique from custom router")
                .build();
        LlmRouter reflectionRouter = (config, ctx) -> {
            selects.incrementAndGet();
            return reflectionClient;
        };

        // Main loop: one unproductive turn (no tool, no final answer → finishReason "length")
        // triggers ReflAct's streak reflection, then a final answer closes the task.
        LlmClient mainLlm = ScriptedLlmClient.script()
                .then(new LlmCompletion("thinking", 1, 1, "length", null))
                .thenFinalAnswer("done")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(mainLlm)
                .reflectionRouter(reflectionRouter)
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("agent-reflact"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("reflact")
                .strategyConfig(new StrategyConfig.ReflAct(1, 1, true, "critic"))
                .build());

        AgentResponse resp = agent.execute(AgentTask.of("do it"));

        assertTrue(resp.isSuccess());
        assertTrue(selects.get() >= 1, "the injected reflection router must have been consulted");
        assertFalse(selects.get() == 0);
    }
}
