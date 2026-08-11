package io.ara.runtime.wiring;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmTransport;
import io.ara.core.mcp.McpClient;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the MCP half of {@link DefaultWiringFactory} (ADR-039): connections opened
 * lazily, deduplicated across agents/sessions referencing the same server id, and
 * closed only once every lease on them is released.
 */
class DefaultWiringFactoryMcpTest {

    /** Minimal fake MCP connection: counts opens (via the connector) and closes. */
    private static final class FakeMcpClient implements McpClient {
        final String id;
        final AtomicInteger closeCount = new AtomicInteger();
        FakeMcpClient(String id) { this.id = id; }
        @Override public CompletableFuture<List<McpTool>> listTools() {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
            return CompletableFuture.completedFuture(McpToolResult.success("ok"));
        }
        @Override public void close() { closeCount.incrementAndGet(); }
        boolean isClosed() { return closeCount.get() > 0; }
    }

    private static AgentConfig configWithMcpServer(String... serverIds) {
        return AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.of("m"))
                .mcpServerIds(List.of(serverIds))
                .build();
    }

    private DefaultResourceRegistry<LlmTransport, LlmClient> namedLlmRegistry() {
        DefaultResourceRegistry<LlmTransport, LlmClient> registry =
                new DefaultResourceRegistry<>(t -> { throw new UnsupportedOperationException(); }, c -> { });
        registry.seed("m", new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw new UnsupportedOperationException();
            }
            @Override public String providerId() { return "m"; }
        });
        return registry;
    }

    @Test
    void twoAgentsReferencingSameServerId_shareTheSameMcpClientInstance() {
        AtomicInteger opens = new AtomicInteger();
        DefaultResourceRegistry<java.util.function.Supplier<McpClient>, McpClient> mcpTransports =
                DefaultResourceRegistry.forCloseable(java.util.function.Supplier::get);
        McpServerBinding binding = new McpServerBinding(
                () -> { opens.incrementAndGet(); return new FakeMcpClient("fs"); },
                client -> List.of());

        var factory = new DefaultWiringFactory(namedLlmRegistry(), "m",
                mcpTransports, Map.of("filesystem-mcp", binding), cfg -> ToolRegistry.empty());

        AgentConfig config = configWithMcpServer("filesystem-mcp");
        AgentWiring wiringA = factory.build(config);
        AgentWiring wiringB = factory.build(config);

        assertEquals(1, opens.get(), "the connector must be invoked lazily, exactly once, on first use");
        wiringA.close();
        wiringB.close();
    }

    @Test
    void mcpConnection_neverClosedWhileItIsStillTheCurrentVersion() {
        // A resource that has never been retired by a publish() stays alive at zero
        // ref-count — it's shared, ambient infrastructure, not torn down just because
        // no session happens to reference it at this instant (same rule as the LLM
        // transports in DefaultResourceRegistryTest.publish_currentVersionIsNeverClosed...).
        DefaultResourceRegistry<java.util.function.Supplier<McpClient>, McpClient> mcpTransports =
                DefaultResourceRegistry.forCloseable(java.util.function.Supplier::get);
        FakeMcpClient client = new FakeMcpClient("fs");
        McpServerBinding binding = new McpServerBinding(() -> client, c -> List.of());

        var factory = new DefaultWiringFactory(namedLlmRegistry(), "m",
                mcpTransports, Map.of("filesystem-mcp", binding), cfg -> ToolRegistry.empty());

        AgentConfig config = configWithMcpServer("filesystem-mcp");
        AgentWiring wiringA = factory.build(config);
        AgentWiring wiringB = factory.build(config);

        wiringA.close();
        wiringB.close();

        assertFalse(client.isClosed(), "the current (never-retired) version must not be closed by ref-count alone");
    }

    @Test
    void mcpConnection_closedOnceRetiredAndItsLastLeaseReleased() {
        DefaultResourceRegistry<java.util.function.Supplier<McpClient>, McpClient> mcpTransports =
                DefaultResourceRegistry.forCloseable(java.util.function.Supplier::get);
        FakeMcpClient clientV1 = new FakeMcpClient("fs-v1");
        McpServerBinding binding = new McpServerBinding(() -> clientV1, c -> List.of());

        var factory = new DefaultWiringFactory(namedLlmRegistry(), "m",
                mcpTransports, Map.of("filesystem-mcp", binding), cfg -> ToolRegistry.empty());

        AgentConfig config = configWithMcpServer("filesystem-mcp");
        AgentWiring wiringA = factory.build(config);   // opens clientV1 lazily, on first use
        AgentWiring wiringB = factory.build(config);   // shares the same lease/version

        // Simulates AgentFactory.publishMcpServer: retires v1, publishes v2.
        mcpTransports.publish("filesystem-mcp", () -> new FakeMcpClient("fs-v2"));
        assertFalse(clientV1.isClosed(), "retired version must stay open while wiringA/wiringB still hold leases on it");

        wiringA.close();
        assertFalse(clientV1.isClosed(), "must stay open until the LAST lease on it releases");

        wiringB.close();
        assertTrue(clientV1.isClosed(), "must close once retired AND its last lease releases");
    }

    @Test
    void mcpServerIdWithNoRegisteredBinding_fallsThroughToStatelessToolRegistry() {
        DefaultResourceRegistry<java.util.function.Supplier<McpClient>, McpClient> mcpTransports =
                DefaultResourceRegistry.forCloseable(java.util.function.Supplier::get);
        AraTool statelessTool = fakeTool("search");

        var factory = new DefaultWiringFactory(namedLlmRegistry(), "m",
                mcpTransports, Map.of(), cfg -> new ToolRegistry() {
                    @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(statelessTool); }
                    @Override public java.util.Optional<AraTool> findById(String toolId) {
                        return toolId.equals("search") ? java.util.Optional.of(statelessTool) : java.util.Optional.empty();
                    }
                    @Override public ToolResult execute(String toolId, String argumentJson) {
                        return ToolResult.success(toolId, "ok");
                    }
                });

        AgentWiring wiring = factory.build(configWithMcpServer("unregistered-server"));
        assertTrue(wiring.toolRegistry().findById("search").isPresent());
        wiring.close();
    }

    @Test
    void mcpToolsAreComposedWithStatelessTools() {
        DefaultResourceRegistry<java.util.function.Supplier<McpClient>, McpClient> mcpTransports =
                DefaultResourceRegistry.forCloseable(java.util.function.Supplier::get);
        AraTool mcpTool = fakeTool("mcp_tool");
        McpServerBinding binding = new McpServerBinding(() -> new FakeMcpClient("fs"), c -> List.of(mcpTool));
        AraTool statelessTool = fakeTool("stateless_tool");

        var factory = new DefaultWiringFactory(namedLlmRegistry(), "m",
                mcpTransports, Map.of("filesystem-mcp", binding), cfg -> new ToolRegistry() {
                    @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(statelessTool); }
                    @Override public java.util.Optional<AraTool> findById(String toolId) {
                        return toolId.equals("stateless_tool") ? java.util.Optional.of(statelessTool) : java.util.Optional.empty();
                    }
                    @Override public ToolResult execute(String toolId, String argumentJson) {
                        return ToolResult.success(toolId, "ok");
                    }
                });

        AgentWiring wiring = factory.build(configWithMcpServer("filesystem-mcp"));
        assertTrue(wiring.toolRegistry().findById("mcp_tool").isPresent());
        assertTrue(wiring.toolRegistry().findById("stateless_tool").isPresent());
        wiring.close();
    }

    @Test
    void transactionalBuild_mixedLlmAndMcpFailure_rollsBackTheLlmLeaseToo() {
        DefaultResourceRegistry<java.util.function.Supplier<McpClient>, McpClient> mcpTransports =
                DefaultResourceRegistry.forCloseable(java.util.function.Supplier::get);
        McpServerBinding failingBinding = new McpServerBinding(
                () -> { throw new RuntimeException("connection refused"); },
                c -> List.of());

        DefaultResourceRegistry<LlmTransport, LlmClient> llmTransports = namedLlmRegistry();
        var factory = new DefaultWiringFactory(llmTransports, "m",
                mcpTransports, Map.of("broken-mcp", failingBinding), cfg -> ToolRegistry.empty());

        AgentConfig config = configWithMcpServer("broken-mcp");

        assertThrows(RuntimeException.class, () -> factory.build(config));

        // The LLM lease acquired before the MCP failure must have been released, not
        // left dangling — pinning "m" again must still resolve cleanly.
        Lease<LlmClient> reacquired = llmTransports.pin("m");
        assertEquals("m", reacquired.get().providerId());
        reacquired.close();
    }

    private static AraTool fakeTool(String id) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return "fake"; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(id, "ok"); }
        };
    }
}
