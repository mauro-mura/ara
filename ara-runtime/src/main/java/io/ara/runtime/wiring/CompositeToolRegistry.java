package io.ara.runtime.wiring;

import io.ara.core.agent.AgentTask;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges MCP-leased {@link AraTool}s with a {@code base} registry (the agent's usual,
 * stateless tool registry) — ADR-039. MCP-owned tool ids take precedence; anything else
 * falls through to {@code base} unchanged, including {@code base}'s own decorators
 * (telemetry, delegation, …).
 */
final class CompositeToolRegistry implements ToolRegistry {

    private final Map<String, AraTool> mcpTools;
    private final ToolRegistry base;

    CompositeToolRegistry(List<AraTool> mcpTools, ToolRegistry base) {
        Map<String, AraTool> byId = new LinkedHashMap<>();
        mcpTools.forEach(t -> byId.put(t.toolId(), t));
        this.mcpTools = Map.copyOf(byId);
        this.base = base;
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        List<AraTool> result = new ArrayList<>();
        List<String> remaining = new ArrayList<>();
        for (String id : enabledToolIds) {
            AraTool mcp = mcpTools.get(id);
            if (mcp != null) result.add(mcp);
            else remaining.add(id);
        }
        result.addAll(base.resolveEnabled(remaining));
        return List.copyOf(result);
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        AraTool mcp = mcpTools.get(toolId);
        return mcp != null ? Optional.of(mcp) : base.findById(toolId);
    }

    /** {@code base}'s full catalog plus every MCP-leased tool; MCP-owned ids take precedence, matching {@link #findById}. */
    @Override
    public List<AraTool> all() {
        Map<String, AraTool> byId = new LinkedHashMap<>();
        base.all().forEach(t -> byId.put(t.toolId(), t));
        byId.putAll(mcpTools);
        return List.copyOf(byId.values());
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        AraTool mcp = mcpTools.get(toolId);
        return mcp != null ? mcp.execute(argumentJson) : base.execute(toolId, argumentJson);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        AraTool mcp = mcpTools.get(toolId);
        return mcp != null ? mcp.execute(argumentJson, task) : base.execute(toolId, argumentJson, task);
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return base.wrapForPropagation(task);
    }
}
