package io.ara.runtime.fs;

import io.ara.core.fs.AgentFileSystem;
import io.ara.core.fs.AgentFileSystemException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Thread-safe, in-process implementation of {@link AgentFileSystem}.
 *
 * <p>All content is stored in an in-memory {@link HashMap}: the {@link AgentFileSystem}
 * operations issue no filesystem syscalls, which eliminates path-traversal, symlink, and
 * cross-process interference risks entirely. The only disk access is the explicit
 * {@link #exportTo} / {@link #importFrom} persistence bridge.
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
 *
 * <p><b>Thread safety.</b> Thread-safe. A single {@link #lock} guards the map, and every
 * operation takes it — including the read paths, which are cheap in-memory lookups, so the
 * cost is negligible and the reasoning stays simple. The lock is required for correctness,
 * not just hygiene: the file quota is a whole-map invariant, and a concurrent map's
 * {@code compute} only locks one bin, so two writers could each observe one free slot and
 * both insert, exceeding {@code maxFiles}. One lock makes the quota exact.
 *
 * <p>No blocking I/O is ever performed while holding the lock: {@link #exportTo} copies a
 * point-in-time snapshot under the lock and writes to disk afterwards, while
 * {@link #importFrom} reads and truncates files first and only then takes the lock to insert.
 */
public final class InMemoryAgentFileSystem implements AgentFileSystem {

    private static final int DEFAULT_MAX_FILES           = 50;
    private static final int DEFAULT_MAX_FILE_SIZE_CHARS = 100_000;
    private static final String TRUNCATION_NOTICE =
            "\n[truncated on import — original file exceeded limit]";

    private final Map<String, String> files = new HashMap<>();
    private final Object lock = new Object();
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
        validateContent(filename, content);
        synchronized (lock) {
            if (!files.containsKey(filename) && files.size() >= maxFiles) {
                throw new AgentFileSystemException(quotaExceededMessage(filename));
            }
            files.put(filename, content);
        }
    }

    @Override
    public void append(String filename, String content) {
        AgentFileSystem.validateFilename(filename);
        validateContent(filename, content);
        synchronized (lock) {
            String existing = files.get(filename);
            if (existing == null && files.size() >= maxFiles) {
                throw new AgentFileSystemException(quotaExceededMessage(filename));
            }
            String merged = (existing == null ? "" : existing) + content;
            if (merged.length() > maxFileSizeChars) {
                throw new AgentFileSystemException(sizeLimitExceededMessage(filename));
            }
            files.put(filename, merged);
        }
    }

    @Override
    public Optional<String> read(String filename) {
        AgentFileSystem.validateFilename(filename);
        synchronized (lock) {
            return Optional.ofNullable(files.get(filename));
        }
    }

    @Override
    public List<String> list() {
        synchronized (lock) {
            return files.keySet().stream().sorted().toList();
        }
    }

    @Override
    public boolean delete(String filename) {
        AgentFileSystem.validateFilename(filename);
        synchronized (lock) {
            return files.remove(filename) != null;
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Exports a point-in-time snapshot of this filesystem to {@code directory} on the real disk.
     *
     * <p>Each in-memory file becomes a file with the same name under {@code directory}.
     * The directory is created if absent. Existing files with the same name are overwritten.
     * Files written after the snapshot is taken are not part of the export; disk I/O happens
     * outside the lock so it never blocks other operations.
     *
     * @param directory target directory on the real filesystem
     * @throws UncheckedIOException if a write fails
     */
    public void exportTo(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        Map<String, String> snapshot = snapshot();
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                Files.writeString(directory.resolve(entry.getKey()), entry.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("exportTo failed: " + e.getMessage(), e);
        }
    }

    /**
     * Imports files from {@code directory} on the real disk into this filesystem.
     *
     * <p>Only plain files are imported; directories and symbolic links are skipped, as are
     * names rejected by {@link AgentFileSystem#validateFilename}. Files are read, truncated and
     * sorted by name before any state is touched, then stored under the same quota rules as
     * {@link #write}: an existing file is always overwritten, while a new file that does not
     * fit within {@code maxFiles} is skipped. Content longer than {@code maxFileSizeChars} is
     * truncated with a notice appended, never exceeding the limit.
     *
     * @param directory source directory on the real filesystem
     * @return number of files imported (created or overwritten)
     * @throws UncheckedIOException if a read fails
     */
    public int importFrom(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        if (!Files.isDirectory(directory)) return 0;

        List<Map.Entry<String, String>> read = readImportableFiles(directory);
        return storeImported(read);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> snapshot() {
        synchronized (lock) {
            return new HashMap<>(files);
        }
    }

    private List<Map.Entry<String, String>> readImportableFiles(Path directory) {
        List<Map.Entry<String, String>> read = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        try {
                            AgentFileSystem.validateFilename(name);
                            read.add(Map.entry(name, truncatedForImport(Files.readString(path))));
                        } catch (IllegalArgumentException ignored) {
                            // skip names that cannot be stored in this filesystem
                        } catch (IOException e) {
                            throw new UncheckedIOException("importFrom failed reading " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("importFrom failed: " + e.getMessage(), e);
        }
        read.sort(Map.Entry.comparingByKey());
        return read;
    }

    private int storeImported(List<Map.Entry<String, String>> read) {
        int imported = 0;
        synchronized (lock) {
            for (Map.Entry<String, String> entry : read) {
                String name = entry.getKey();
                if (!files.containsKey(name) && files.size() >= maxFiles) {
                    continue; // quota reached: new files beyond the limit are skipped
                }
                files.put(name, entry.getValue());
                imported++;
            }
        }
        return imported;
    }

    private String truncatedForImport(String content) {
        if (content.length() <= maxFileSizeChars) return content;
        if (maxFileSizeChars <= TRUNCATION_NOTICE.length()) {
            return TRUNCATION_NOTICE.substring(0, maxFileSizeChars);
        }
        return content.substring(0, maxFileSizeChars - TRUNCATION_NOTICE.length())
                + TRUNCATION_NOTICE;
    }

    private void validateContent(String filename, String content) {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        if (content.length() > maxFileSizeChars) {
            throw new AgentFileSystemException(sizeLimitExceededMessage(filename));
        }
    }

    private String quotaExceededMessage(String filename) {
        return "File quota exceeded: cannot create '" + filename
                + "' (limit=" + maxFiles + ")";
    }

    private String sizeLimitExceededMessage(String filename) {
        return "File size limit exceeded for '" + filename
                + "' (limit=" + maxFileSizeChars + " chars)";
    }
}
