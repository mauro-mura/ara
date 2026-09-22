package io.ara.runtime.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AraYamlLoader}: the dependency-free {@code ara.yml} reader that feeds
 * {@link AraRuntimeConfig}. Until these tests, none of its behaviors (nested-key
 * flattening, comment stripping, env substitution, search order) were pinned.
 */
class AraYamlLoaderTest {

    @TempDir
    Path tmp;

    private static final String NEVER_SET_ENV = "ARA_YAML_LOADER_TEST_NEVER_SET_123";

    private Path writeYaml(String content) throws IOException {
        Path path = tmp.resolve("ara.yml");
        Files.writeString(path, content);
        return path;
    }

    // ── flattening ──────────────────────────────────────────────────────────

    @Test
    void nestedMappings_flattenToDottedKeys() throws IOException {
        Path yml = writeYaml("""
                ara:
                  runtime:
                    name: my-ara
                    startupTimeoutSec: 15
                """);

        Map<String, String> parsed = AraYamlLoader.loadFromPath(yml);

        assertEquals(2, parsed.size());
        assertEquals("my-ara", parsed.get("ara.runtime.name"));
        assertEquals("15", parsed.get("ara.runtime.startupTimeoutSec"));
    }

    @Test
    void siblingKeys_underSameParent_doNotPolluteEachOther() throws IOException {
        Path yml = writeYaml("""
                ara:
                  runtime:
                    name: my-ara
                  persistence:
                    mode: memory
                """);

        Map<String, String> parsed = AraYamlLoader.loadFromPath(yml);

        assertEquals("memory", parsed.get("ara.persistence.mode"));
        assertEquals("my-ara", parsed.get("ara.runtime.name"));
        assertFalse(parsed.containsKey("ara.mode"), "persistence and runtime must stay separate subtrees");
    }

    // ── comments and blank lines ────────────────────────────────────────────

    @Test
    void comments_andBlankLines_areIgnored() throws IOException {
        Path yml = writeYaml("""
                # top-level comment
                ara:
                  runtime:
                    name: my-ara # inline comment is stripped too

                  # comment between sibling keys
                  persistence:
                    mode: memory
                """);

        Map<String, String> parsed = AraYamlLoader.loadFromPath(yml);

        assertEquals("my-ara", parsed.get("ara.runtime.name"));
        assertEquals("memory", parsed.get("ara.persistence.mode"));
        assertEquals(2, parsed.size());
    }

    // ── indentation ─────────────────────────────────────────────────────────

    @Test
    void tabIndent_isTreatedAsTwoSpaces() throws IOException {
        Path yml = writeYaml("""
                ara:
                \truntime:
                \t\tname: my-ara
                """);

        Map<String, String> parsed = AraYamlLoader.loadFromPath(yml);

        assertEquals("my-ara", parsed.get("ara.runtime.name"),
                "a tab counts as 2 indent spaces — same subtree depth as two spaces");
    }

    // ── environment variable substitution ───────────────────────────────────

    @Test
    void envVar_withDefault_resolvesToDefaultWhenUnset() {
        assertEquals("fallback", AraYamlLoader.resolveEnvVars("${" + NEVER_SET_ENV + ":fallback}"));
    }

    @Test
    void envVar_withoutDefault_resolvesToEmptyWhenUnset() {
        assertEquals("", AraYamlLoader.resolveEnvVars("${" + NEVER_SET_ENV + "}"));
    }

    @Test
    void noEnvVarToken_leavesValueUntouched() {
        assertEquals("./data/ara", AraYamlLoader.resolveEnvVars("./data/ara"));
        assertEquals("plain text", AraYamlLoader.resolveEnvVars("plain text"));
    }

    // ── search order / error paths ──────────────────────────────────────────

    @Test
    void load_picksUpClasspathAraYml() {
        // The src/test/resources/ara.yml fixture lives on the test classpath.
        Map<String, String> parsed = AraYamlLoader.load();

        assertFalse(parsed.isEmpty(), "the test-classpath ara.yml fixture must be found");
        assertEquals("my-ara", parsed.get("ara.runtime.name"));
        assertEquals("15", parsed.get("ara.runtime.startupTimeoutSec"));
    }

    @Test
    void loadFromPath_missingFile_throws() {
        Path missing = tmp.resolve("does-not-exist.yml");
        assertThrows(IllegalArgumentException.class, () -> AraYamlLoader.loadFromPath(missing));
    }

    @Test
    void parse_returnsAnImmutableMap() throws IOException {
        Path yml = writeYaml("""
                ara:
                  runtime:
                    name: my-ara
                """);
        Map<String, String> parsed = AraYamlLoader.loadFromPath(yml);

        assertThrows(UnsupportedOperationException.class, () -> parsed.put("k", "v"));
        assertTrue(parsed.keySet().contains("ara.runtime.name"));
    }
}