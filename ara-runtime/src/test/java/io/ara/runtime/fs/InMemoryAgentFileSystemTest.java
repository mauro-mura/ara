package io.ara.runtime.fs;

import io.ara.core.fs.AgentFileSystem;
import io.ara.core.fs.AgentFileSystemException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAgentFileSystemTest {

    @Test
    void writeAndRead_roundTrip() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();
        fs.write("notes.txt", "hello");

        assertEquals("hello", fs.read("notes.txt").orElseThrow());
        assertEquals(List.of("notes.txt"), fs.list());
    }

    @Test
    void read_missingFile_isEmpty() {
        assertTrue(new InMemoryAgentFileSystem().read("absent.txt").isEmpty());
    }

    @Test
    void write_replacesExistingContent() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();
        fs.write("a.txt", "first");
        fs.write("a.txt", "second");

        assertEquals("second", fs.read("a.txt").orElseThrow());
        assertEquals(1, fs.list().size());
    }

    @Test
    void append_createsThenConcatenates() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();
        fs.append("log.txt", "a");
        fs.append("log.txt", "b");

        assertEquals("ab", fs.read("log.txt").orElseThrow());
    }

    @Test
    void list_isASortedSnapshot() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();
        fs.write("c.txt", "c");
        fs.write("a.txt", "a");
        fs.write("b.txt", "b");

        assertEquals(List.of("a.txt", "b.txt", "c.txt"), fs.list());
    }

    @Test
    void delete_reportsWhetherTheFileExisted() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();
        fs.write("a.txt", "a");

        assertTrue(fs.delete("a.txt"));
        assertFalse(fs.delete("a.txt"));
        assertTrue(fs.read("a.txt").isEmpty());
    }

    @Test
    void write_rejectsInvalidFilenames() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();

        assertThrows(IllegalArgumentException.class, () -> fs.write("", "x"));
        assertThrows(IllegalArgumentException.class, () -> fs.write("dir/a.txt", "x"));
        assertThrows(IllegalArgumentException.class, () -> fs.write("dir\\a.txt", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> fs.write("a".repeat(AgentFileSystem.MAX_FILENAME_LENGTH + 1), "x"));
    }

    @Test
    void write_rejectsContentOverTheSizeLimit() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(10, 5);

        assertThrows(AgentFileSystemException.class, () -> fs.write("a.txt", "123456"));
        assertThrows(IllegalArgumentException.class, () -> fs.write("a.txt", null));
    }

    @Test
    void write_enforcesTheFileQuota() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(2, 100);
        fs.write("a.txt", "a");
        fs.write("b.txt", "b");

        assertThrows(AgentFileSystemException.class, () -> fs.write("c.txt", "c"));
        assertEquals(2, fs.list().size());
    }

    @Test
    void write_stillAllowsOverwritingAnExistingFileAtQuota() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(1, 100);
        fs.write("a.txt", "old");

        fs.write("a.txt", "new");

        assertEquals("new", fs.read("a.txt").orElseThrow());
    }

    @Test
    void append_toExistingFileAtQuota_keepsTheFile() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(1, 100);
        fs.write("a.txt", "old");

        fs.append("a.txt", "er");

        assertEquals("older", fs.read("a.txt").orElseThrow());
        assertEquals(1, fs.list().size());
    }

    @Test
    void append_ofNewFileAtQuota_throwsWithoutStoring() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(1, 100);
        fs.write("a.txt", "a");

        assertThrows(AgentFileSystemException.class, () -> fs.append("b.txt", "b"));
        assertTrue(fs.read("b.txt").isEmpty());
        assertEquals("a", fs.read("a.txt").orElseThrow());
    }

    @Test
    void append_rejectsMergedContentOverTheSizeLimit() {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(10, 5);
        fs.write("a.txt", "123");

        assertThrows(AgentFileSystemException.class, () -> fs.append("a.txt", "456"));
        assertEquals("123", fs.read("a.txt").orElseThrow());
    }

    @Test
    void concurrentWrites_neverExceedTheQuota() throws InterruptedException {
        int quota = 5;
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(quota, 100);
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String name = "file-" + i + ".txt";
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    fs.write(name, "x");
                    accepted.incrementAndGet();
                } catch (AgentFileSystemException expected) {
                    // quota reached
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();

        assertEquals(quota, accepted.get());
        assertEquals(quota, fs.list().size());
    }

    @Test
    void importFrom_readsRegularFiles(@TempDir Path source) throws IOException {
        Files.writeString(source.resolve("a.txt"), "alpha");
        Files.writeString(source.resolve("b.txt"), "beta");
        Files.createDirectory(source.resolve("nested"));

        int imported = new InMemoryAgentFileSystem().importFrom(source);

        assertEquals(2, imported);
    }

    @Test
    void importFrom_missingDirectory_returnsZero(@TempDir Path source) {
        assertEquals(0, new InMemoryAgentFileSystem().importFrom(source.resolve("absent")));
    }

    @Test
    void importFrom_skipsSymbolicLinks(@TempDir Path source) throws IOException {
        Files.writeString(source.resolve("real.txt"), "secret");
        Files.createSymbolicLink(source.resolve("link.txt"), source.resolve("real.txt"));

        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();
        int imported = fs.importFrom(source);

        assertEquals(1, imported);
        assertEquals(List.of("real.txt"), fs.list());
    }

    @Test
    void importFrom_enforcesTheFileQuotaDeterministically(@TempDir Path source) throws IOException {
        Files.writeString(source.resolve("c.txt"), "c");
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(source.resolve("b.txt"), "b");

        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(2, 100);
        int imported = fs.importFrom(source);

        assertEquals(2, imported);
        assertEquals(List.of("a.txt", "b.txt"), fs.list());
    }

    @Test
    void importFrom_truncatesOversizeFilesToTheLimit(@TempDir Path source) throws IOException {
        Files.writeString(source.resolve("big.txt"), "x".repeat(500));

        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem(10, 80);
        fs.importFrom(source);

        String content = fs.read("big.txt").orElseThrow();
        assertEquals(80, content.length());
        assertTrue(content.contains("truncated"));
    }

    @Test
    void exportTo_writesAllFilesToDisk(@TempDir Path target) throws IOException {
        InMemoryAgentFileSystem fs = new InMemoryAgentFileSystem();
        fs.write("a.txt", "alpha");
        fs.write("b.txt", "beta");

        fs.exportTo(target);

        assertEquals("alpha", Files.readString(target.resolve("a.txt")));
        assertEquals("beta", Files.readString(target.resolve("b.txt")));
    }

    @Test
    void persistence_roundTripsThroughDisk(@TempDir Path target) throws IOException {
        InMemoryAgentFileSystem source = new InMemoryAgentFileSystem();
        source.write("a.txt", "alpha");
        source.exportTo(target);

        InMemoryAgentFileSystem restored = new InMemoryAgentFileSystem();
        assertEquals(1, restored.importFrom(target));
        assertEquals("alpha", restored.read("a.txt").orElseThrow());
    }
}
