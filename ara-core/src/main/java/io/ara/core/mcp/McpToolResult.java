package io.ara.core.mcp;

/**
 * Immutable result of an MCP tool invocation.
 *
 * @param content text content returned by the tool (empty string on error)
 * @param isError {@code true} if the server signaled an error condition
 */
public record McpToolResult(String content, boolean isError) {

    public McpToolResult {
        if (content == null) content = "";
    }

    public static McpToolResult success(String content) {
        return new McpToolResult(content, false);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(message, true);
    }
}
