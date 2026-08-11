package io.ara.runtime.fs;

import io.ara.core.fs.AgentFileSystem;
import io.ara.core.fs.AgentFileSystemException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Thread-safe, in-process implementation of {@link AgentFileSystem}.
 *
 * <p>All content is stored in a {@link ConcurrentHashMap} — no filesystem
 * syscalls are ever issued. This eliminates path-traversal, symlink, and
 * cross-process interference risks entirely.
 *
 * <p>Two optional limits guard against agent misbehaviour:
 * <ul>
 *   <li>{@code maxFiles} — maximum number of distinct files (default: 50).</li>
 *   <li>{@code maxFileSizeChars} — maximum characters per file (default: 100 000).</li>
 * </ul>
 *
 * <p>Files are scoped to this instance and are lost when the instance is
 * garbage-collected. For cross-session persistence, wire a remote-storage
 * implementation instead.
 */
public final class InMemoryAgentFileSystem implements AgentFileSystem {

    private static final int DEFAULT_MAX_FILES          = 50;
    private static final int DEFAULT_MAX_FILE_SIZE_CHARS = 100_000;

    private final Map<String, String> files = new ConcurrentHashMap<>();
    private final int maxFiles;
    private final int maxFileSizeChars;

    /** Creates an instance with default limits (50 files, 100 000 chars each). */
    public InMemoryAgentFileSystem() {
        this(DEFAULT_MAX_FILES, DEFAULT_MAX_FILE_SIZE_CHARS);
    }

    /**
     * @param maxFiles          maximum number of distinct files allowed
     * @param maxFileSizeChars  maximum characters per file
     */
    public InMemoryAgentFileSystem(int maxFiles, int maxFileSizeChars) {
        if (maxFiles < 1)         throw new IllegalArgumentException("maxFiles must be >= 1");
        if (maxFileSizeChars < 1) throw new IllegalArgumentException("maxFileSizeChars must be >= 1");
        this.maxFiles          = maxFiles;
        this.maxFileSizeChars  = maxFileSizeChars;
    }

    @Override
    public void write(String filename, String content) {
        AgentFileSystem.validateFilename(filename);
        validateContent(content);
        if (!files.containsKey(filename) && files.size() >= maxFiles) {
            throw new AgentFileSystemException(
                    "File quota exceeded: cannot create '" + filename
                    + "' (limit=" + maxFiles + ")");
        }
        files.put(filename, content);
    }

    @Override
    public void append(String filename, String content) {
        AgentFileSystem.validateFilename(filename);
        validateContent(content);
        files.merge(filename, content, (existing, added) -> {
            String merged = existing + added;
            if (merged.length() > maxFileSizeChars) {
                throw new AgentFileSystemException(
                        "File size limit exceeded for '" + filename
                        + "' (limit=" + maxFileSizeChars + " chars)");
            }
            return merged;
        });
        // If file was new, check quota after insert
        if (files.size() > maxFiles) {
            files.remove(filename);
            throw new AgentFileSystemException(
                    "File quota exceeded: cannot create '" + filename
                    + "' (limit=" + maxFiles + ")");
        }
    }

    @Override
    public Optional<String> read(String filename) {
        AgentFileSystem.validateFilename(filename);
        return Optional.ofNullable(files.get(filename));
    }

    @Override
    public List<String> list() {
        return List.copyOf(files.keySet());
    }

    @Override
    public boolean delete(String filename) {
        AgentFileSystem.validateFilename(filename);
        return files.remove(filename) != null;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Exports all files in this filesystem to {@code directory} on the real disk.
     *
     * <p>Each in-memory file becomes a file with the same name under {@code directory}.
     * The directory is created if absent. Existing files with the same name are overwritten.
     *
     * @param directory target directory on the real filesystem
     * @throws UncheckedIOException if a write fails
     */
    public void exportTo(Path directory) {
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Files.writeString(directory.resolve(entry.getKey()), entry.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("exportTo failed: " + e.getMessage(), e);
        }
    }

    /**
     * Imports files from {@code directory} on the real disk into this filesystem.
     *
     * <p>Only plain files (non-directories) are imported.
     * Files whose names are invalid per {@link AgentFileSystem#validateFilename} are skipped.
     * If a file's content exceeds {@code maxFileSizeChars} it is truncated with a notice appended.
     *
     * @param directory source directory on the real filesystem
     * @return number of files successfully imported
     * @throws UncheckedIOException if a read fails
     */
    public int importFrom(Path directory) {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> entries = Files.list(directory)) {
            int[] count = {0};
            entries.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                try {
                    AgentFileSystem.validateFilename(name);
                    String content = Files.readString(p);
                    if (content.length() > maxFileSizeChars) {
                        content = content.substring(0, maxFileSizeChars)
                                + "\n[truncated on import — original file exceeded limit]";
                    }
                    files.put(name, content);
                    count[0]++;
                } catch (IllegalArgumentException ignored) {
                    // skip files with invalid names
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return count[0];
        } catch (IOException e) {
            throw new UncheckedIOException("importFrom failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateContent(String content) {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        if (content.length() > maxFileSizeChars) {
            throw new AgentFileSystemException(
                    "Content exceeds max file size of " + maxFileSizeChars + " chars");
        }
    }
}