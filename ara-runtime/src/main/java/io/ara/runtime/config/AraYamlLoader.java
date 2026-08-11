package io.ara.runtime.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal YAML reader that flattens a simple nested YAML file into a
 * {@code Map<String, String>} of dot-separated keys.
 *
 * <p>Supports:
 * <ul>
 *   <li>Scalar values: {@code key: value}</li>
 *   <li>Nested mapping via indentation</li>
 *   <li>Environment variable substitution: {@code ${ENV_VAR}} and
 *       {@code ${ENV_VAR:default}}</li>
 *   <li>{@code #} comments (stripped)</li>
 * </ul>
 *
 * <p>Does NOT support: lists, multi-line strings, anchors, tags, or quoted
 * strings containing {@code :}. For those, add a full YAML library.
 *
 * <p>Search order for {@code ara.yml}:
 * <ol>
 *   <li>Path provided explicitly</li>
 *   <li>{@code ./ara.yml} in the current working directory</li>
 *   <li>{@code ara.yml} on the classpath root</li>
 * </ol>
 */
public final class AraYamlLoader {

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private AraYamlLoader() {}

    /**
     * Loads and resolves {@code ara.yml} from the default search path.
     *
     * @return flattened key→value map; empty if no file is found
     */
    public static Map<String, String> load() {
        // 1) current working directory
        Path cwd = Paths.get("ara.yml");
        if (Files.isReadable(cwd)) {
            return loadFromPath(cwd);
        }
        // 2) classpath
        try (InputStream is = AraYamlLoader.class.getClassLoader()
                .getResourceAsStream("ara.yml")) {
            if (is != null) {
                return parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) { /* fall through */ }

        return Map.of();
    }

    /**
     * Loads and resolves the YAML file at the given path.
     *
     * @param path path to the YAML file
     * @return flattened key→value map
     * @throws IllegalArgumentException if the file cannot be read
     */
    public static Map<String, String> loadFromPath(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read ara.yml from: " + path, e);
        }
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    static Map<String, String> parse(BufferedReader reader) throws IOException {
        Map<String, String> result = new HashMap<>();
        // Stack of (indent, key-segment) pairs to build dotted path
        Deque<int[]> indentStack = new ArrayDeque<>();  // int[]{indent}
        Deque<String> keyStack   = new ArrayDeque<>();

        String line;
        while ((line = reader.readLine()) != null) {
            // Strip inline comments, skip blank / comment lines
            int commentIdx = line.indexOf('#');
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx);
            }
            String trimmed = line.stripTrailing();
            if (trimmed.isBlank()) continue;

            int indent = leadingSpaces(trimmed);
            String content = trimmed.stripLeading();

            int colonIdx = content.indexOf(':');
            if (colonIdx < 0) continue;  // unrecognised line

            String key   = content.substring(0, colonIdx).trim();
            String value = content.substring(colonIdx + 1).trim();

            // Pop stack entries whose indent >= current
            while (!indentStack.isEmpty() && indentStack.peek()[0] >= indent) {
                indentStack.pop();
                keyStack.pop();
            }

            if (value.isEmpty()) {
                // Mapping node — push onto stack
                indentStack.push(new int[]{indent});
                keyStack.push(key);
            } else {
                // Scalar — build dotted key and store
                String fullKey = buildKey(keyStack, key);
                result.put(fullKey, resolveEnvVars(value));
            }
        }
        return Map.copyOf(result);
    }

    private static String buildKey(Deque<String> stack, String leaf) {
        if (stack.isEmpty()) return leaf;
        StringBuilder sb = new StringBuilder();
        // Stack is LIFO; reverse to get top-to-bottom order
        String[] segments = stack.toArray(new String[0]);
        for (int i = segments.length - 1; i >= 0; i--) {
            sb.append(segments[i]).append('.');
        }
        sb.append(leaf);
        return sb.toString();
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 2; // treat tab as 2 spaces
            else break;
        }
        return count;
    }

    /**
     * Replaces {@code ${VAR}} and {@code ${VAR:default}} tokens with their
     * environment-variable values.
     */
    static String resolveEnvVars(String value) {
        if (value == null || !value.contains("${")) return value;
        Matcher m = ENV_VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName     = m.group(1);
            String defaultVal  = m.group(2); // null if no default
            String envVal      = System.getenv(varName);
            String replacement = (envVal != null && !envVal.isBlank())
                    ? envVal
                    : (defaultVal != null ? defaultVal : "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
