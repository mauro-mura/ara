package io.ara.adapters.mcp;

import io.ara.core.mcp.McpClient;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * ARA {@link McpClient} that delegates to the official MCP Java SDK's
 * {@link McpSyncClient}, bridging its blocking calls to {@link CompletableFuture}
 * via virtual threads.
 *
 * <p>Use {@link McpClientFactory} to construct instances.
 */
public class AraMcpClientAdapter implements McpClient {

    private final McpSyncClient sdkClient;
    private final Executor executor;

    AraMcpClientAdapter(McpSyncClient sdkClient, Executor executor) {
        this.sdkClient = sdkClient;
        this.executor  = executor;
    }

    @Override
    public CompletableFuture<List<McpTool>> listTools() {
        return CompletableFuture.supplyAsync(
                () -> sdkClient.listTools().tools().stream()
                        .map(McpToolMapper::from)
                        .toList(),
                executor);
    }

    @Override
    public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
        return CompletableFuture.supplyAsync(
                () -> McpToolMapper.from(sdkClient.callTool(new McpSchema.CallToolRequest(name, args))),
                executor);
    }

    @Override
    public void close() {
        sdkClient.close();
    }
}
