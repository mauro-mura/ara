package io.ara.runtime.contract;

import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the content of a specific Markdown code fence from the LLM output.
 *
 * <p>Unlike {@link MarkdownFenceStripper} (which strips the outer fence of the whole
 * response), this processor searches for a fence annotated with a given language tag
 * and returns only its inner content. Use it when the LLM returns a mixed response
 * combining explanatory prose and a code block:
 *
 * <pre>{@code
 * // Extract the Java block from a response that also contains prose
 * CodeFenceExtractor.forLanguage("java")
 *
 * // Extract the first anonymous block (```...```)
 * CodeFenceExtractor.forLanguage("")
 * }</pre>
 *
 * <p>If no matching fence is found the payload is returned unchanged (pass-through).
 * If multiple fences with the same tag exist, the first one is returned.
 */
public final class CodeFenceExtractor implements OutputProcessor {

    private final String  language;
    private final Pattern pattern;

    private CodeFenceExtractor(String language) {
        this.language = Objects.requireNonNull(language, "language must not be null");
        String tag = language.isBlank() ? "\\s*" : "\\s*" + Pattern.quote(language) + "\\s*";
        this.pattern = Pattern.compile("```" + tag + "\\n(.*?)```", Pattern.DOTALL);
    }

    /** Extracts the content of the first {@code ```<language>} fence. */
    public static CodeFenceExtractor forLanguage(String language) {
        return new CodeFenceExtractor(language);
    }

    /** Convenience: extracts Java code blocks. */
    public static CodeFenceExtractor java()   { return new CodeFenceExtractor("java"); }

    /** Convenience: extracts JSON code blocks. */
    public static CodeFenceExtractor json()   { return new CodeFenceExtractor("json"); }

    /** Convenience: extracts Python code blocks. */
    public static CodeFenceExtractor python() { return new CodeFenceExtractor("python"); }

    /** Convenience: extracts SQL code blocks. */
    public static CodeFenceExtractor sql()    { return new CodeFenceExtractor("sql"); }

    /** Convenience: extracts the first anonymous (untagged) code block. */
    public static CodeFenceExtractor anonymous() { return new CodeFenceExtractor(""); }

    @Override
    public ProcessingResult process(String output) {
        if (output == null) return ProcessingResult.pass("");
        Matcher m = pattern.matcher(output);
        if (m.find()) {
            return ProcessingResult.pass(m.group(1).strip());
        }
        // no matching fence — pass through unchanged (the output may already be plain code)
        return ProcessingResult.pass(output);
    }

    public String language() { return language; }
}
