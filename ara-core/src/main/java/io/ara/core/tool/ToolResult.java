package io.ara.core.tool;

import java.util.Objects;

/**
 * The result of a single {@link AraTool} execution.
 *
 * <p>All factory methods enforce the invariant that a successful result always has
 * non-null output and a failed result always has a non-null error message.
 *
 * @param toolId  the identifier of the tool that produced this result
 * @param output  the string output of the tool (may be JSON or plain text); never {@code null}
 * @param success {@code true} if the tool completed without error
 * @param error   human-readable error message; {@code null} on success
 */
public record ToolResult(String toolId, String output, boolean success, String error) {

    public ToolResult {
        Objects.requireNonNull(toolId, "toolId must not be null");
        if (toolId.isBlank()) throw new IllegalArgumentException("toolId must not be blank");
        Objects.requireNonNull(output, "output must not be null");
        if (!success && (error == null || error.isBlank()))
            throw new IllegalArgumentException("error must be provided when success is false");
        if (success && error != null)
            throw new IllegalArgumentException("error must be null when success is true");
    }

    /** Returns {@code true} if the tool completed successfully. */
    public boolean isSuccess() {
        return success;
    }

    /** Returns {@code true} if the tool execution failed. */
    public boolean isFailed() {
        return !success;
    }

    /**
     * Returns the relevant content for this result:
     * {@code output} on success, {@code error} on failure.
     * Convenience method for consumers that display a single string regardless of outcome.
     */
    public String content() {
        return success ? output : error;
    }

    /** Factory method for a successful tool result. */
    public static ToolResult success(String toolId, String output) {
        return new ToolResult(toolId, output, true, null);
    }

    /** Factory method for a failed tool result. */
    public static ToolResult failure(String toolId, String error) {
        return new ToolResult(toolId, "", false,
                Objects.requireNonNull(error, "error must not be null"));
    }
}