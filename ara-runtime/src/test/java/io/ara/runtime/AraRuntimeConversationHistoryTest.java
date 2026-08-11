package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.core.agent.ConversationTurn;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AraRuntime#conversationHistory} must return the recorded turns regardless of
 * whether the registered agent is a raw {@code AgentInstance} or the
 * {@code ContractEnforcingAgent} decorator that {@code AgentFactory} wraps around it
 * whenever a non-empty {@link AgentContract} is supplied.
 */
class AraRuntimeConversationHistoryTest {

    private static AgentConfig configFor(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))   // matches the per-agent named llmClient
                .plannerStrategy("react")
                .maxIterations(5)
                .maxConversationTurns(4)   // turns are only recorded when > 0
                .build();
    }

    private static AraRuntime runtimeFor(AgentId id) {
        return AraRuntime.builder()
                .llmClient(id.value(), ScriptedLlmClient.script()
                        .thenFinalAnswer("done")
                        .build())
                .build();
    }

    @Test
    void history_visible_for_plain_agent() {
        AgentId id = AgentId.of("plain-agent");
        AraRuntime runtime = runtimeFor(id);
        try {
            AraAgent agent = runtime.createAgent(configFor(id));
            SessionId session = SessionId.of("s1");

            AgentResponse r = agent.execute(AgentTask.of("hello").withSessionId(session));
            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());

            List<ConversationTurn> turns = runtime.conversationHistory(id, session);
            assertEquals(1, turns.size());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void history_visible_through_contract_decorator() {
        AgentId id = AgentId.of("contract-agent");
        AraRuntime runtime = runtimeFor(id);
        try {
            AgentContract contract = AgentContract.builder()
                    .addInputProcessor(input -> ProcessingResult.pass(input))
                    .build();
            AraAgent agent = runtime.createAgent(configFor(id), contract);
            SessionId session = SessionId.of("s1");

            AgentResponse r = agent.execute(AgentTask.of("hello").withSessionId(session));
            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());

            List<ConversationTurn> turns = runtime.conversationHistory(id, session);
            assertEquals(1, turns.size(),
                    "history must be reachable through the ContractEnforcingAgent decorator");
        } finally {
            runtime.stop();
        }
    }
}