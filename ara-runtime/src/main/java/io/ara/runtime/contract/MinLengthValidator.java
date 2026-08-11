package io.ara.runtime.contract;

import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

/**
 * Rejects the output if it is shorter than {@code minChars} characters (after trimming).
 * Useful to detect empty or near-empty LLM responses.
 */
public final class MinLengthValidator implements OutputProcessor {

    private final int minChars;

    public MinLengthValidator(int minChars) {
        if (minChars <= 0) throw new IllegalArgumentException("minChars must be > 0");
        this.minChars = minChars;
    }

    public static MinLengthValidator atLeast(int minChars) { return new MinLengthValidator(minChars); }

    @Override
    public ProcessingResult process(String output) {
        if (output == null || output.strip().length() < minChars) {
            return ProcessingResult.reject(
                    "Output too short: expected at least %d chars, got %d"
                            .formatted(minChars, output == null ? 0 : output.strip().length()));
        }
        return ProcessingResult.pass(output);
    }
}
