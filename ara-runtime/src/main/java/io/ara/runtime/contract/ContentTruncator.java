package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

/**
 * Truncates the input to at most {@code maxChars} characters.
 * Used to keep the agent's context window within budget.
 */
public final class ContentTruncator implements InputProcessor {

    private final int maxChars;

    public ContentTruncator(int maxChars) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be > 0");
        this.maxChars = maxChars;
    }

    public static ContentTruncator to(int maxChars) { return new ContentTruncator(maxChars); }

    @Override
    public ProcessingResult process(String input) {
        if (input == null || input.length() <= maxChars) return ProcessingResult.pass(input == null ? "" : input);
        return ProcessingResult.pass(input.substring(0, maxChars));
    }
}
