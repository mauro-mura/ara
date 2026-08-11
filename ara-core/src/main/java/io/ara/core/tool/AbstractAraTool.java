package io.ara.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class AbstractAraTool implements AraTool {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public final ToolResult execute(String argumentJson) {
        try {
            return doExecute(MAPPER.readTree(argumentJson));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(toolId(), toolId() + " interrupted");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(toolId(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure(toolId(), toolId() + " failed: " + e.getMessage());
        }
    }

    protected abstract ToolResult doExecute(JsonNode args) throws Exception;
}