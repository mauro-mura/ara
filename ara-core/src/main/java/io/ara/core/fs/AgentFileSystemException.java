package io.ara.core.fs;

/**
 * Thrown by {@link AgentFileSystem} implementations when an operational error
 * occurs — quota exceeded, file not found on mandatory read, backend failure, etc.
 *
 * <p>Distinct from {@link IllegalArgumentException} (invalid filename) so callers
 * can handle the two failure modes differently.
 */
public class AgentFileSystemException extends RuntimeException {

    private static final long serialVersionUID = -7698871670877799974L;

	public AgentFileSystemException(String message) {
        super(message);
    }

    public AgentFileSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}