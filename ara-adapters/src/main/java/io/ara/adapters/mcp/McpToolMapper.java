package io.ara.adapters.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.stream.Collectors;

/** Stateless mapper between MCP SDK types and ARA core records. */
final class McpToolMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolMapper() {}

    static McpTool from(McpSchema.Tool sdkTool) {
        JsonNode schema = toJsonNode(sdkTool.inputSchema());
        return new McpTool(sdkTool.name(), sdkTool.description(), schema);
    }

    static McpToolResult from(McpSchema.CallToolResult sdkResult) {
        String content = extractText(sdkResult.content());
        boolean isError = Boolean.TRUE.equals(sdkResult.isError());
        return new McpToolResult(content, isError);
    }

    private static JsonNode toJsonNode(Object inputSchema) {
        if (inputSchema == null) return MAPPER.createObjectNode();
        try {
            if (inputSchema instanceof JsonNode node) return node;
            return MAPPER.valueToTree(inputSchema);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String extractText(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) return "";
        return contents.stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}
