package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

import java.util.regex.Pattern;

/**
 * Normalizes whitespace in a payload so downstream processors can rely on
 * consistent formatting regardless of how the LLM or caller formatted the text.
 *
 * <p>Operations applied in order:
 * <ol>
 *   <li>Convert all line endings ({@code \r\n}, {@code \r}) to {@code \n}</li>
 *   <li>Collapse runs of horizontal whitespace (spaces/tabs) to a single space
 *       within each line</li>
 *   <li>Remove trailing whitespace from every line</li>
 *   <li>Collapse runs of more than two consecutive blank lines to a single blank line</li>
 *   <li>Strip leading and trailing whitespace from the whole payload</li>
 * </ol>
 *
 * <p>Can be used as both an {@link InputProcessor} and an {@link OutputProcessor}.
 */
public final class WhitespaceNormalizer implements InputProcessor, OutputProcessor {

    private static final WhitespaceNormalizer INSTANCE = new WhitespaceNormalizer();

    /** Runs of horizontal whitespace inside a line, collapsed to one space. */
    private static final Pattern HORIZONTAL_RUN = Pattern.compile("[ \\t]+");
    /** Three or more consecutive blank (whitespace-only) lines, collapsed to one blank line. */
    private static final Pattern BLANK_RUN = Pattern.compile("(\n[ \\t]*){3,}");

    private WhitespaceNormalizer() {}

    public static WhitespaceNormalizer instance() { return INSTANCE; }

    @Override
    public ProcessingResult process(String payload) {
        if (payload == null) return ProcessingResult.pass("");
        String result = payload
                .replace("\r\n", "\n")
                .replace("\r", "\n");
        // collapse horizontal whitespace within each line; trim trailing spaces
        String[] lines = result.split("\n", -1);
        StringBuilder sb = new StringBuilder(result.length());
        for (String line : lines) {
            sb.append(HORIZONTAL_RUN.matcher(line).replaceAll(" ").stripTrailing()).append('\n');
        }
        result = sb.toString();
        // collapse 3+ consecutive blank lines to one blank line
        result = BLANK_RUN.matcher(result).replaceAll("\n\n");
        return ProcessingResult.pass(result.strip());
    }
}
