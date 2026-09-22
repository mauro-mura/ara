package io.ara.examples.support;

import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The tool-registry boilerplate every example used to hand-roll: resolve an id to a tool,
 * look it up, dispatch. {@link #registry(AraTool...)} wraps a small immutable catalog; the
 * {@code AraTool}s themselves — schemas and {@code execute} logic — stay in each example,
 * because that is the educational content.
 */
public final class Tools {

    /** A {@link ToolRegistry} over an immutable catalog of exactly these tools. */
    public static ToolRegistry registry(AraTool... tools) {
        return registry(List.of(tools));
    }

    /** A {@link ToolRegistry} over an immutable catalog of exactly these tools. */
    public static ToolRegistry registry(List<AraTool> tools) {
        return new CatalogRegistry(tools);
    }

    private static final class CatalogRegistry implements ToolRegistry {

        private final Map<String, AraTool> byId = new LinkedHashMap<>();

        CatalogRegistry(List<AraTool> tools) {
            for (AraTool t : tools) byId.put(t.toolId(), t);
        }

        @Override
        public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
            return enabledToolIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        @Override
        public List<AraTool> all() {
            return List.copyOf(byId.values());
        }

        @Override
        public Optional<AraTool> findById(String toolId) {
            return Optional.ofNullable(byId.get(toolId));
        }

        @Override
        public ToolResult execute(String toolId, String argumentJson) {
            AraTool tool = byId.get(toolId);
            if (tool == null) return ToolResult.failure(toolId, "Tool not found: " + toolId);
            return tool.execute(argumentJson);
        }
    }

    private Tools() { }
}
