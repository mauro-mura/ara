package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

/**
 * Rejects input or output that exceeds {@code maxChars} characters (after trimming).
 * Can be used as both an {@link InputProcessor} and an {@link OutputProcessor}.
 */
public final class MaxLengthValidator implements InputProcessor, OutputProcessor {

    private final int maxChars;

    public MaxLengthValidator(int maxChars) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be > 0");
        this.maxChars = maxChars;
    }

    public static MaxLengthValidator atMost(int maxChars) { return new MaxLengthValidator(maxChars); }

    @Override
    public ProcessingResult process(String payload) {
        if (payload == null) return ProcessingResult.pass("");
        if (payload.strip().length() > maxChars) {
            return ProcessingResult.reject(
                    "Payload too long: expected at most %d chars, got %d"
                            .formatted(maxChars, payload.strip().length()));
        }
        return ProcessingResult.pass(payload);
    }
}
