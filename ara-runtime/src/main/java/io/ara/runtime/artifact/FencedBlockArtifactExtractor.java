package io.ara.runtime.artifact;

import io.ara.core.artifact.ArtifactExtractor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts every fenced code block of an answer as an artifact named {@code block-N.<extension>}.
 *
 * <h2>Why the language goes in the name</h2>
 * <p>Every part is stored as {@code text/plain}. The media vocabulary is closed on purpose — it also
 * decides what a task may carry <em>in</em> — and widening it to a family of {@code text/x-<language>}
 * types would change that for a feature that only needs to say "this is Python". The extension of the
 * name says it, and costs nothing.
 *
 * <h2>What counts as a block</h2>
 * <p>An opening fence of three backticks at the start of a line (with an optional language), a closing
 * fence at the start of a later line. A fence that is never closed is not a block, and a block with only
 * whitespace in it is skipped: an empty artifact is not an artifact. Blocks are numbered in the order they
 * are kept, so {@code block-1} is the first block that has content.
 *
 * <p>Known limit: a block that itself contains a line starting with three backticks (a Markdown document
 * quoting a fence) ends at that line, as it does in any CommonMark reader that does not use longer fences.
 *
 * <p>Thread-safe: immutable.
 */
public final class FencedBlockArtifactExtractor implements ArtifactExtractor {

    private static final Pattern FENCE =
            Pattern.compile("(?ms)^[ \\t]*```[ \\t]*([\\w+#.-]*)[ \\t]*\\R(.*?)^[ \\t]*```[ \\t]*$");

    /** The declared languages whose usual extension differs from their name. */
    private static final Map<String, String> EXTENSION_OF = Map.of(
            "python", "py", "javascript", "js", "typescript", "ts", "shell", "sh", "bash", "sh",
            "markdown", "md", "yml", "yaml");

    private static final Pattern PLAIN_LANGUAGE = Pattern.compile("[a-z0-9]{1,12}");

    @Override
    public List<Extracted> extract(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<Extracted> parts = new ArrayList<>();
        Matcher fence = FENCE.matcher(content);
        while (fence.find()) {
            String code = withoutFinalLineBreak(fence.group(2));
            if (code.isBlank()) {
                continue;
            }
            String name = "block-" + (parts.size() + 1) + extensionOf(fence.group(1));
            parts.add(new Extracted(name, "text/plain", code.getBytes(StandardCharsets.UTF_8)));
        }
        return List.copyOf(parts);
    }

    private static String withoutFinalLineBreak(String code) {
        if (code.endsWith("\r\n")) {
            return code.substring(0, code.length() - 2);
        }
        return code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
    }

    /** {@code .py} for {@code python}; empty when no language was declared or it is not a plain word ({@code c++}). */
    private static String extensionOf(String declaredLanguage) {
        String language = declaredLanguage.toLowerCase(Locale.ROOT);
        String extension = EXTENSION_OF.getOrDefault(language, language);
        return PLAIN_LANGUAGE.matcher(extension).matches() ? "." + extension : "";
    }
}
