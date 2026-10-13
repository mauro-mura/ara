package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.MemoryScope;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0091 D3: a builder that calls {@code memoryManagerScopeFactory(...)} hands the scoped
 * factory the {@code MemoryScope} — the agent config <em>and</em> the session id that
 * {@code AgentFactory} used to discard at the exact point it built the per-session manager.
 * Supplying both memory factories is refused at build time, like toolRegistry/toolRegistryFactory.
 */
class AraRuntimeMemoryScopeWiringTest {

    /** Minimal deterministic client: every turn answers immediately, no tools. */
    private static LlmClient answeringClient() {
        return new LlmClient() {
            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 5, 5, "stop", null);
            }

            @Override
            public String providerId() { return "recording"; }
        };
    }

    private static AgentConfig.Builder baseConfig(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))
                .plannerStrategy("react")
                .maxIterations(3)
                .maxConversationTurns(20);
    }

    @Test
    void scopedFactory_receivesTheConfigAndEachSessionId() {
        AgentId id = AgentId.of("scoped-agent");
        List<MemoryScope> received = new CopyOnWriteArrayList<>();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), answeringClient())
                .memoryManagerScopeFactory(scope -> {
                    received.add(scope);
                    return new InMemoryMemoryManager();
                })
                .build();
        AraAgent agent = runtime.createAgent(baseConfig(id).build());

        AgentResponse first = agent.execute(
                AgentTask.of("one").withSessionId(SessionId.of("s1")));
        assertTrue(first.isSuccess(), () -> "execute failed: " + first.failureReason());
        AgentResponse second = agent.execute(
                AgentTask.of("two").withSessionId(SessionId.of("s2")));
        assertTrue(second.isSuccess(), () -> "execute failed: " + second.failureReason());

        assertEquals(2, received.size(), "one scope per session manager built");
        assertEquals(id.value(), received.get(0).config().agentId().value());
        assertEquals("s1", received.get(0).session().value());
        assertEquals("s2", received.get(1).session().value());
    }

    @Test
    void bothMemoryFactories_refusedAtBuildTime() {
        AgentId id = AgentId.of("both-agent");
        AraRuntime.Builder builder = AraRuntime.builder()
                .llmClient(id.value(), answeringClient())
                .memoryManagerFactory(cfg -> new InMemoryMemoryManager())
                .memoryManagerScopeFactory(scope -> new InMemoryMemoryManager());
        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("memoryManagerScopeFactory"),
                () -> "unexpected message: " + e.getMessage());
    }
}
