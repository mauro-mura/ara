package io.ara.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable representation of a tool advertised by an MCP server.
 *
 * @param name        unique tool name within the server
 * @param description human-readable description shown to the LLM
 * @param inputSchema JSON Schema describing the tool's input parameters
 */
public record McpTool(String name, String description, JsonNode inputSchema) {

    public McpTool {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("McpTool name must not be blank");
    }
}
