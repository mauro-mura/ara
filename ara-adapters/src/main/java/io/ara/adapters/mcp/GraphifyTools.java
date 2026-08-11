package io.ara.adapters.mcp;

import io.ara.core.tool.AraTool;

import java.util.List;
import java.util.Objects;

/**
 * Convenience wiring for graphify's shared HTTP MCP server
 * (<a href="https://github.com/Graphify-Labs/graphify">Graphify-Labs/graphify</a> —
 * "Shared HTTP server" mode, started with e.g.
 * {@code python -m graphify.serve graphify-out/graph.json --transport http --port 8080}).
 *
 * <p>Bridges {@link McpClientFactory#fromStreamableHttp} + {@link McpToolRegistry} +
 * {@link McpAraTool} into a ready-to-register {@link AraTool} list, exposing whatever
 * tools the server currently advertises — the server's {@code tools/list} response is the
 * source of truth, nothing here hard-codes tool names or schemas. As of writing that
 * includes {@code query_graph}, {@code get_node}, {@code get_neighbors},
 * {@code shortest_path}, {@code list_prs}, {@code get_pr_impact}, and {@code triage_prs}.
 *
 * <p>For one-off/scripted use, {@link #tools()} gives an immediate {@code List<AraTool>}
 * snapshot. To wire graphify into {@link io.ara.runtime.AraRuntime} with ADR-039's lazy,
 * shared/ref-counted MCP connection lifecycle instead (recommended for long-lived agents),
 * skip this class and register {@link McpClientFactory#fromStreamableHttp} directly via
 * {@code AraRuntime.Builder.mcpServer(id, connector, toolsAdapter)} — see
 * {@code io.ara.examples.basics.GraphifyMcpExample} for a full example of both styles.
 *
 * <p>Usage:
 * <pre>{@code
 * try (GraphifyTools graphify = GraphifyTools.connect("http://localhost:8080", apiKey)) {
 *     for (AraTool tool : graphify.tools()) {
 *         System.out.println(tool.toolId() + ": " + tool.description());
 *     }
 * } // closes the underlying MCP session
 * }</pre>
 */
public final class GraphifyTools implements AutoCloseable {

    private final AraMcpClientAdapter client;
    private final McpToolRegistry     registry;
    private final List<AraTool>       tools;

    private GraphifyTools(AraMcpClientAdapter client, McpToolRegistry registry, List<AraTool> tools) {
        this.client   = client;
        this.registry = registry;
        this.tools    = tools;
    }

    /** Connects to an unauthenticated graphify HTTP server (no {@code --api-key} configured). */
    public static GraphifyTools connect(String serverUrl) {
        return connect(serverUrl, null);
    }

    /**
     * Connects to a graphify HTTP server.
     *
     * @param serverUrl base URL, e.g. {@code http://localhost:8080} (the {@code /mcp}
     *                  mount path is appended automatically)
     * @param apiKey    bearer token matching the server's {@code --api-key} /
     *                  {@code GRAPHIFY_API_KEY}, or {@code null}/blank if unset
     */
    public static GraphifyTools connect(String serverUrl, String apiKey) {
        Objects.requireNonNull(serverUrl, "serverUrl must not be null");
        AraMcpClientAdapter client   = McpClientFactory.fromStreamableHttp(serverUrl, apiKey);
        McpToolRegistry     registry = new McpToolRegistry(client);
        List<AraTool> tools = registry.getTools().join().stream()
                .map(t -> (AraTool) new McpAraTool(t, registry))
                .toList();
        return new GraphifyTools(client, registry, tools);
    }

    /** The graphify server's tools, ready to register in any {@link io.ara.core.tool.ToolRegistry}. */
    public List<AraTool> tools() {
        return tools;
    }

    /** The underlying MCP client, e.g. to hand to {@code AraRuntime.Builder.mcpServer}'s connector. */
    public AraMcpClientAdapter client() {
        return client;
    }

    /**
     * The underlying tool-list cache — call {@link McpToolRegistry#invalidate()} on it and
     * re-fetch via {@link McpToolRegistry#getTools()} if the server's tool set can change
     * at runtime (e.g. a future {@code ToolListChanged} notification).
     */
    public McpToolRegistry registry() {
        return registry;
    }

    /** Closes the underlying MCP session. */
    @Override
    public void close() {
        client.close();
    }
}