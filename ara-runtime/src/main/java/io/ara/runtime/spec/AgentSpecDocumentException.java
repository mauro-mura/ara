package io.ara.runtime.spec;

/**
 * An agent document that cannot be turned into an {@code AgentSpec} (or an {@code AgentSpec}
 * that cannot be written as a document), with the path of the offending field.
 *
 * <p>An {@link IllegalArgumentException} subclass on purpose: every domain constructor the
 * decoder ends up calling ({@code LlmProfile}, {@code ExecutionConfig}, ...) already rejects
 * bad input with one, so a caller that catches {@code IllegalArgumentException} around an
 * import sees one failure family, and one that wants the path catches this type.
 *
 * <p>Immutable; thread-safe.
 */
public final class AgentSpecDocumentException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String path;

    public AgentSpecDocumentException(String path, String detail) {
        super(message(path, detail));
        this.path = path;
    }

    public AgentSpecDocumentException(String path, String detail, Throwable cause) {
        super(message(path, detail), cause);
        this.path = path;
    }

    /** Dotted path of the field at fault ({@code execution.maxIterations}); empty for the root. */
    public String path() {
        return path;
    }

    private static String message(String path, String detail) {
        return (path.isEmpty() ? "document" : path) + ": " + detail;
    }
}
