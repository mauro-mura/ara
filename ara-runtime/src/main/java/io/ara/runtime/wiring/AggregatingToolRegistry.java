package io.ara.runtime.wiring;

import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only discovery view over every {@link ToolRegistry} a {@code
 * AraRuntime.Builder#toolRegistryFactory} has produced so far, one per agent created.
 *
 * <p>{@code AraRuntime#toolRegistry()} has no single instance to point at when a
 * per-agent factory is configured — different agents can be wired to genuinely
 * different registries — so it backs itself with this instead: the union of every
 * per-agent registry's {@link #all()}, keyed by tool id so two agents exposing a
 * same-named tool don't produce a duplicate entry. Backed by the live map {@code
 * AraRuntime.Builder} populates as agents are created, so agents created after this
 * instance was constructed are still visible on the next call — nothing needs
 * rebuilding.
 *
 * <p>Not wired into any agent's actual tool dispatch — each agent still calls its own
 * per-agent registry directly. This exists purely for enumeration surfaces (e.g. an
 * AgentOS-compat {@code GET /registry?resource_type=tool} route), so {@link
 * #resolveEnabled} and {@link #execute} — which only make sense in the context of one
 * specific agent's selection — degrade the same way {@link ToolRegistry#empty()} does.
 */
public final class AggregatingToolRegistry implements ToolRegistry {

    private final Map<String, ToolRegistry> perAgentRegistries;

    /**
     * @param perAgentRegistries live, growing map from agent id to the {@link
     *                           ToolRegistry} the factory produced for it; read fresh on
     *                           every call, not snapshotted at construction time
     */
    public AggregatingToolRegistry(Map<String, ToolRegistry> perAgentRegistries) {
        this.perAgentRegistries = perAgentRegistries;
    }

    /** The union of every per-agent registry's own {@code all()}, deduplicated by tool id. */
    @Override
    public List<AraTool> all() {
        Map<String, AraTool> byId = new LinkedHashMap<>();
        for (ToolRegistry registry : perAgentRegistries.values()) {
            for (AraTool tool : registry.all()) {
                byId.putIfAbsent(tool.toolId(), tool);
            }
        }
        return List.copyOf(byId.values());
    }

    /** Searches every accumulated per-agent registry in turn; first match wins. */
    @Override
    public Optional<AraTool> findById(String toolId) {
        for (ToolRegistry registry : perAgentRegistries.values()) {
            Optional<AraTool> found = registry.findById(toolId);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        return List.of();
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        return ToolResult.failure(toolId, "No tool registry configured");
    }
}
