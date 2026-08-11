package io.ara.runtime.strategy;

import io.ara.core.tool.AraTool;

import java.util.List;

/**
 * Formats the tool catalogue for inclusion in a system prompt.
 *
 * <p>Centralising this logic eliminates the inconsistency where
 * {@code ReactStrategy} returned an empty string on an empty list
 * while {@code PlanExecuteStrategy} returned an explicit sentence.
 * The canonical behaviour is an empty string (the LLM infers "no tools"
 * from the absence of the section).
 */
public final class ToolCatalogFormatter {

    private ToolCatalogFormatter() {}

    /**
     * Returns a human-readable tool catalogue to append to the system prompt,
     * or an empty string when {@code tools} is empty.
     */
    public static String format(List<AraTool> tools) {
        if (tools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nAvailable tools:\n");
        for (AraTool tool : tools) {
            sb.append("- ").append(tool.toolId())
              .append(": ").append(tool.description()).append("\n")
              .append("  Arguments: ").append(tool.argumentSchema()).append("\n");
        }
        return sb.toString();
    }
}
