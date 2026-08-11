package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentInstanceContext;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.agent.InstanceContextStore;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ADR-036 wiring on {@link AraRuntime}: per-agent
 * {@code toolRegistryFactory}, the shared {@link InstanceContextStore}, and cleanup on
 * agent destruction.
 */
class AraRuntimeInstanceContextTest {

    /** Tool that records the instance-context value it saw at execution time. */
    private static final class EchoCtxTool implements AraTool {
        private final AgentInstanceContext ctx;
        private final AtomicReference<String> seen;

        EchoCtxTool(AgentInstanceContext ctx, AtomicReference<String> seen) {
            this.ctx = ctx;
            this.seen = seen;
        }

        @Override public String toolId() { return "echo_ctx"; }
        @Override public String description() { return "records ctx.get(\"secret\")"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }

        @Override
        public ToolResult execute(String argumentJson) {
            seen.set(ctx.get("secret"));
            return ToolResult.success(toolId(), "ok");
        }
    }

    private static ToolRegistry singleTool(AraTool tool) {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool); }
            @Override public Optional<AraTool> findById(String id) {
                return tool.toolId().equals(id) ? Optional.of(tool) : Optional.empty();
            }
            @Override public ToolResult execute(String id, String json) {
                return tool.toolId().equals(id) ? tool.execute(json) : ToolResult.failure(id, "not found");
            }
        };
    }

    private static AgentConfig configFor(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))   // matches the per-agent named llmClient
                .plannerStrategy("react")
                .enabledTools(List.of("echo_ctx"))
                .maxIterations(5)
                .build();
    }

    @Test
    void toolRegistryFactory_toolReadsPerAgentInstanceContext() {
        InstanceContextStore store = new InstanceContextStore();
        AtomicReference<String> seenA = new AtomicReference<>();
        AtomicReference<String> seenB = new AtomicReference<>();

        AraRuntime runtime = AraRuntime.builder()
                // Each agent gets its OWN ScriptedLlmClient — the script queue is consumed
                // sequentially per instance, so sharing one client across agents would let
                // the second agent's execute() drain an already-empty queue.
                .llmClient("agentA", ScriptedLlmClient.script()
                        .thenToolCall("echo_ctx", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .llmClient("agentB", ScriptedLlmClient.script()
                        .thenToolCall("echo_ctx", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .instanceContextStore(store)
                .toolRegistryFactory(agentCfg -> {
                    AgentId id = agentCfg.agentId();
                    AtomicReference<String> target = "agentA".equals(id.value()) ? seenA : seenB;
                    return singleTool(new EchoCtxTool(store.forAgent(id), target));
                })
                .build();

        store.set(AgentId.of("agentA"), Map.of("secret", "s1"));
        store.set(AgentId.of("agentB"), Map.of("secret", "s2"));

        AraAgent agentA = runtime.createAgent(configFor(AgentId.of("agentA")));
        AraAgent agentB = runtime.createAgent(configFor(AgentId.of("agentB")));

        AgentResponse ra = agentA.execute(AgentTask.of("hi"));
        AgentResponse rb = agentB.execute(AgentTask.of("hi"));

        assertTrue(ra.isSuccess(), ra.failureReason());
        assertTrue(rb.isSuccess(), rb.failureReason());
        assertEquals("s1", seenA.get(), "agentA's tool must see agentA's own instance context");
        assertEquals("s2", seenB.get(), "agentB's tool must see agentB's own instance context, not agentA's");
    }

    @Test
    void toolRegistry_and_toolRegistryFactory_areMutuallyExclusive() {
        AraRuntime.Builder builder = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .toolRegistry(ToolRegistry.empty())
                .toolRegistryFactory(cfg -> ToolRegistry.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("toolRegistry"));
    }

    @Test
    void instanceContextStore_autoCreatedWhenNotSupplied() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .build();

        assertNotNull(runtime.instanceContextStore());
    }

    @Test
    void instanceContextStore_explicitlySupplied_isReturnedAsIs() {
        InstanceContextStore store = new InstanceContextStore();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .instanceContextStore(store)
                .build();

        assertSame(store, runtime.instanceContextStore());
    }

    @Test
    void destroyAgent_clearsInstanceContextStoreEntry() {
        InstanceContextStore store = new InstanceContextStore();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .instanceContextStore(store)
                .build();

        AgentId id = AgentId.of("buddy");
        store.set(id, Map.of("secret", "s1"));
        AraAgent agent = runtime.createAgent(configFor(id));

        assertEquals("s1", store.forAgent(id).get("secret"));
        runtime.destroyAgent(agent);
        assertNull(store.forAgent(id).get("secret"), "destroyAgent must clear the agent's instance context entry");
    }

    @Test
    void stop_clearsInstanceContextStoreEntriesForEveryAgent() {
        InstanceContextStore store = new InstanceContextStore();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("x").build())
                .instanceContextStore(store)
                .build();

        store.set(AgentId.of("agentA"), Map.of("secret", "s1"));
        store.set(AgentId.of("agentB"), Map.of("secret", "s2"));
        runtime.createAgent(configFor(AgentId.of("agentA")));
        runtime.createAgent(configFor(AgentId.of("agentB")));

        runtime.stop();

        assertNull(store.forAgent(AgentId.of("agentA")).get("secret"));
        assertNull(store.forAgent(AgentId.of("agentB")).get("secret"));
    }
}
