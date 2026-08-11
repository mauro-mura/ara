package io.ara.adapters.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts an MCP tool as an {@link AraTool} so it can be registered in any ARA
 * {@link io.ara.core.tool.ToolRegistry}.
 *
 * <p>The MCP server is called synchronously inside {@link #execute} — the
 * {@link McpToolRegistry#callTool} future is joined on the caller's thread.
 * Since agents run on virtual threads this is cheap: the carrier thread is
 * released while the network call is in flight.
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
            return "{}";
        }
    }

    @Override
    public ToolResult execute(String argumentJson) {
        Map<String, Object> args = parseArgs(argumentJson);
        try {
            McpToolResult result = registry.callTool(tool.name(), args).join();
            if (result.isError()) {
                return ToolResult.failure(tool.name(), result.content());
            }
            return ToolResult.success(tool.name(), result.content());
        } catch (Exception e) {
            return ToolResult.failure(tool.name(), "MCP call failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> parseArgs(String argumentJson) {
        if (argumentJson == null || argumentJson.isBlank() || "{}".equals(argumentJson.strip())) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(argumentJson, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
