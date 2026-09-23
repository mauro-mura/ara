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
import java.util.concurrent.ExecutorService;

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
    private final boolean ownsExecutor;

    /**
     * @param ownsExecutor whether this adapter created {@code executor} itself (a fresh,
     *                     single-client {@link McpClientFactory#defaultExecutor()}) rather
     *                     than receiving a caller-supplied one — see {@link #close()}
     *                     (concurrency hardening P4/U13: an executor this adapter created
     *                     for itself must not outlive it; a caller's own executor, possibly
     *                     shared across other clients, must never be shut down by us).
     */
    AraMcpClientAdapter(McpSyncClient sdkClient, Executor executor, boolean ownsExecutor) {
        this.sdkClient    = sdkClient;
        this.executor     = executor;
        this.ownsExecutor = ownsExecutor;
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

    /**
     * Closes the underlying SDK client and, only when this adapter owns it (P4/U13), shuts
     * down the executor too — {@code shutdownNow()}, matching {@code DefaultResourceRegistry
     * .close()}'s own choice (U23/N1's precedent): no graceful drain here, so {@code close()}
     * itself never blocks.
     */
    @Override
    public void close() {
        try {
            sdkClient.close();
        } finally {
            if (ownsExecutor && executor instanceof ExecutorService es) {
                es.shutdownNow();
            }
        }
    }
}
