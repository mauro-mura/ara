package io.ara.runtime.contract;

import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

/**
 * Strips Markdown code-fence delimiters that LLMs commonly wrap around JSON.
 *
 * <p>Handles both typed ({@code ```json}) and untyped ({@code ```}) fences.
 * If no fence is found the output is passed through unchanged.
 */
public final class MarkdownFenceStripper implements OutputProcessor {

    private static final MarkdownFenceStripper INSTANCE = new MarkdownFenceStripper();

    private MarkdownFenceStripper() {}

    public static MarkdownFenceStripper instance() { return INSTANCE; }

    @Override
    public ProcessingResult process(String output) {
        if (output == null) return ProcessingResult.pass("");
        String trimmed = output.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline < 0) return ProcessingResult.pass(trimmed);
            String body = trimmed.substring(firstNewline + 1);
            if (body.endsWith("```")) {
                body = body.substring(0, body.length() - 3);
            }
            return ProcessingResult.pass(body.strip());
        }
        return ProcessingResult.pass(output);
    }
}
