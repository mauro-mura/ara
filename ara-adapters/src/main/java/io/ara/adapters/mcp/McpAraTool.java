package io.ara.adapters.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Adapts an MCP tool as an {@link AraTool} so it can be registered in any ARA
 * {@link io.ara.core.tool.ToolRegistry}.
 *
 * <p>The MCP server is called synchronously inside {@link #execute} — the
 * {@link McpToolRegistry#callTool} future is awaited on the caller's thread via
 * {@link CompletableFuture#get()}, not {@code join()}, so an interrupt (task
 * cancellation, a deadline watchdog) is noticed immediately instead of only after the
 * SDK's own {@code requestTimeout}. Since agents run on virtual threads this is cheap:
 * the carrier thread is released while the network call is in flight.
 *
 * <p>Usage:
 * <pre>{@code
 * McpToolRegistry registry = new McpToolRegistry(McpClientFactory.fromSse("http://localhost:3000/sse"));
 *
 * List<AraTool> tools = registry.getTools().join().stream()
 *     .map(t -> new McpAraTool(t, registry))
 *     .toList();
 * }</pre>
 */
public class McpAraTool implements AraTool {

    private static final Logger log = LoggerFactory.getLogger(McpAraTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final McpTool tool;
    private final McpToolRegistry registry;

    public McpAraTool(McpTool tool, McpToolRegistry registry) {
        this.tool     = Objects.requireNonNull(tool, "tool must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public String toolId() {
        return tool.name();
    }

    @Override
    public String description() {
        return tool.description() != null ? tool.description() : "";
    }

    @Override
    public String argumentSchema() {
        if (tool.inputSchema() == null || tool.inputSchema().isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(tool.inputSchema());
        } catch (Exception e) {
            // Same "{}" as a tool that genuinely takes no parameters, so log it: without
            // this the two are indistinguishable, and a server whose schema fails to
            // serialise silently advertises itself to the model as parameterless.
            log.warn("MCP tool '{}' has an inputSchema that could not be serialised — advertising "
                    + "it as taking no parameters. Schema: {}", tool.name(), tool.inputSchema(), e);
            return "{}";
        }
    }

    @Override
    public ToolResult execute(String argumentJson) {
        Map<String, Object> args;
        try {
            args = parseArgs(argumentJson);
        } catch (IllegalArgumentException e) {
            // Fail rather than call the server with no arguments. An MCP tool invoked
            // without the parameters the model meant to pass still runs and still returns
            // something, so degrading here turns a malformed-arguments bug into a plausible
            // wrong answer — the one failure mode nobody can trace back to this line.
            log.warn("MCP tool '{}' was called with malformed argument JSON — not calling the "
                    + "server. Raw arguments: {}", tool.name(), argumentJson, e);
            return ToolResult.failure(tool.name(), "Malformed argument JSON: " + e.getMessage());
        }

        CompletableFuture<McpToolResult> future = registry.callTool(tool.name(), args);
        try {
            McpToolResult result = future.get();
            if (result.isError()) {
                return ToolResult.failure(tool.name(), result.content());
            }
            return ToolResult.success(tool.name(), result.content());
        } catch (InterruptedException e) {
            // Unlike join(), get() responds to Thread.interrupt() — cancel(true) also
            // interrupts the virtual thread the SDK call is running on, so a call parked
            // on interruptible I/O has a chance to unwind instead of leaking until the
            // SDK's own 30s requestTimeout eventually resolves it.
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.info("MCP tool '{}' call cancelled while waiting for a result", tool.name());
            return ToolResult.failure(tool.name(), "MCP call cancelled");
        } catch (CancellationException e) {
            return ToolResult.failure(tool.name(), "MCP call cancelled");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolResult.failure(tool.name(), "MCP call failed: " + cause.getMessage());
        }
    }

    /**
     * Parses the model's argument JSON into the map the MCP SDK expects.
     *
     * @param argumentJson the raw arguments; {@code null}, blank and {@code "{}"} all mean
     *                     "no arguments", as does a bare JSON {@code null}
     * @return the parsed arguments, never {@code null}
     * @throws IllegalArgumentException if {@code argumentJson} is not a JSON object — the
     *                                  caller turns this into a failed {@link ToolResult}
     */
    private static Map<String, Object> parseArgs(String argumentJson) {
        if (argumentJson == null || argumentJson.isBlank() || "{}".equals(argumentJson.strip())) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(argumentJson, MAP_TYPE);
            return parsed != null ? parsed : Collections.emptyMap();
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
