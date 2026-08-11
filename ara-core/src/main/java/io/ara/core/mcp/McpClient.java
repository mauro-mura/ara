package io.ara.core.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ARA abstraction over an MCP server connection.
 *
 * <p>Implementations are provided in {@code ara-adapters}; this interface
 * intentionally carries no dependency on the MCP SDK.
 *
 * <p>Callers must {@link #close()} the client when done to release transport
 * resources (connections, threads, pipes).
 */
public interface McpClient extends AutoCloseable {

    /**
     * Returns the list of tools currently advertised by the MCP server.
     *
     * @return future completing with a non-null, possibly empty list of tools
     */
    CompletableFuture<List<McpTool>> listTools();

    /**
     * Invokes a named tool on the MCP server.
     *
     * @param name tool name as returned by {@link #listTools()}
     * @param args key-value arguments conforming to the tool's {@code inputSchema}
     * @return future completing with the tool result; never {@code null}
     */
    CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args);

    @Override
    void close();
}
