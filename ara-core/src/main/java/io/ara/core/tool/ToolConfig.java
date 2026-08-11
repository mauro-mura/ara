package io.ara.core.tool;

import java.util.Objects;

/**
 * Immutable, serialisable definition of an {@link AraTool}.
 *
 * <p>Captures the three pieces of metadata that fully describe a tool to the LLM
 * and to the persistence layer, with no runtime dependencies attached.
 *
 * <p>This is intentionally kept minimal. Additional fields (category, version,
 * tags, requiresApproval) should be added only when a concrete persistence
 * requirement demands them.
 *
 * <h2>Persistence contract</h2>
 * <p>A {@code ToolConfig} is the unit stored and loaded by a {@code ToolRepository}.
 * Reconstructing a live {@link AraTool} from a persisted config is the
 * responsibility of a {@code ToolFactory} that maps {@code toolId} to the
 * concrete implementation and injects its runtime dependencies.
 *
 * @param toolId         unique identifier (e.g. {@code "file_read"});
 *                       non-null, non-blank
 * @param description    human-readable description sent to the LLM so it knows
 *                       when and how to call this tool; non-null
 * @param argumentSchema JSON Schema string describing the tool's input;
 *                       non-null, must be valid JSON
 */
public record ToolConfig(
        String toolId,
        String description,
        String argumentSchema
) {
    public ToolConfig {
        Objects.requireNonNull(toolId,         "toolId must not be null");
        Objects.requireNonNull(description,    "description must not be null");
        Objects.requireNonNull(argumentSchema, "argumentSchema must not be null");
        if (toolId.isBlank()) {
            throw new IllegalArgumentException("toolId must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank — the LLM uses it to decide when to call this tool");
        }
        if (argumentSchema.isBlank()) {
            throw new IllegalArgumentException("argumentSchema must not be blank");
        }
    }
}