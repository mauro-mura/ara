package io.ara.runtime.wiring;

import io.ara.core.mcp.McpClient;
import io.ara.core.tool.AraTool;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * How {@code AgentFactory} — via {@link DefaultWiringFactory} — turns a registered MCP
 * server id into leased {@link AraTool}s (ADR-039).
 *
 * <p>{@code ara-runtime} cannot depend on {@code ara-adapters} (module boundary), so it
 * cannot construct an {@code McpAraTool}/{@code McpToolRegistry} itself. The caller —
 * which sits in a module that sees both (e.g. the application composition root) —
 * supplies both halves: {@code connector} opens the connection (invoked lazily, once,
 * by the shared registry on first use), and {@code toolsAdapter} converts an already-open
 * {@link McpClient} into the {@link AraTool}s a session's {@code ToolRegistry} exposes.
 *
 * @param connector    lazily opens the MCP connection; never invoked eagerly at registration
 * @param toolsAdapter converts a leased, already-open {@link McpClient} into {@link AraTool}s
 */
public record McpServerBinding(
        Supplier<McpClient> connector,
        Function<McpClient, List<AraTool>> toolsAdapter
) {
}
