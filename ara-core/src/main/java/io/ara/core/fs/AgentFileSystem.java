package io.ara.core.fs;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over a per-agent file system.
 *
 * <p>Tools ({@code FileReadTool}, {@code FileWriteTool}, …) depend only on this
 * interface so that the underlying storage can be swapped without touching the
 * tools or the agent runtime.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@code InMemoryAgentFileSystem} — zero-syscall in-process storage,
 *       suitable for safe demos and unit tests (in {@code ara-runtime}).</li>
 * </ul>
 *
 * <h2>Filename constraints</h2>
 * All implementations MUST validate filenames before any operation:
 * <ul>
 *   <li>Non-null, non-blank.</li>
 *   <li>No path separators ({@code /} or {@code \}).</li>
 *   <li>No {@code ..} component.</li>
 *   <li>Maximum {@value #MAX_FILENAME_LENGTH} characters.</li>
 * </ul>
 * Implementations should throw {@link IllegalArgumentException} on invalid
 * filenames and {@link AgentFileSystemException} for operational errors
 * (quota exceeded, file not found, etc.).
 */
public interface AgentFileSystem {

    int MAX_FILENAME_LENGTH = 255;

    /**
     * Writes {@code content} to {@code filename}, replacing any existing content.
     *
     * @throws IllegalArgumentException  if the filename is invalid
     * @throws AgentFileSystemException  if a quota or backend limit is exceeded
     */
    void write(String filename, String content);

    /**
     * Appends {@code content} to {@code filename}, creating the file if absent.
     *
     * @throws IllegalArgumentException  if the filename is invalid
     * @throws AgentFileSystemException  if a quota or backend limit is exceeded
     */
    void append(String filename, String content);

    /**
     * Reads the content of {@code filename}.
     *
     * @return the file content, or {@link Optional#empty()} if the file does not exist
     * @throws IllegalArgumentException  if the filename is invalid
     */
    Optional<String> read(String filename);

    /**
     * Returns the names of all files currently in this filesystem.
     *
     * @return an unmodifiable snapshot of the file list; never {@code null}
     */
    List<String> list();

    /**
     * Deletes {@code filename}.
     *
     * @return {@code true} if the file existed and was deleted, {@code false} if not found
     * @throws IllegalArgumentException  if the filename is invalid
     */
    boolean delete(String filename);

    // ── Shared validation ──────────────────────────────────────────────────────

    /**
     * Validates a filename according to the constraints documented on this interface.
     *
     * @throws IllegalArgumentException if the filename is invalid
     */
    static void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be null or blank");
        }
        if (filename.length() > MAX_FILENAME_LENGTH) {
            throw new IllegalArgumentException(
                    "filename exceeds maximum length of " + MAX_FILENAME_LENGTH);
        }
        if (filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException(
                    "filename must not contain path separators: " + filename);
        }
        if (filename.equals("..") || filename.startsWith("../") || filename.startsWith("..\\")) {
            throw new IllegalArgumentException(
                    "filename must not be a path traversal: " + filename);
        }
    }
}