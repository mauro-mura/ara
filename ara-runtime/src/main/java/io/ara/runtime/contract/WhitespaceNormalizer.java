package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

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
            sb.append(line.replaceAll("[ \\t]+", " ").stripTrailing()).append('\n');
        }
        result = sb.toString();
        // collapse 3+ consecutive blank lines to one blank line
        result = result.replaceAll("(\n[ \\t]*){3,}", "\n\n");
        return ProcessingResult.pass(result.strip());
    }
}
