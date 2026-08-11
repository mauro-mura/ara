package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates that a payload matches (or does not match) a regular expression.
 *
 * <p>Use {@link #matching(String)} to require the pattern, or
 * {@link #notMatching(String)} to reject it (e.g. blocking forbidden content).
 * Can be used as both an {@link InputProcessor} and an {@link OutputProcessor}.
 *
 * <pre>{@code
 * // Output must be a semantic version string
 * RegexValidator.matching("\\d+\\.\\d+\\.\\d+.*")
 *
 * // Input must not contain prompt-injection markers
 * RegexValidator.notMatching("(?i)ignore (previous|all) instructions?")
 * }</pre>
 */
public final class RegexValidator implements InputProcessor, OutputProcessor {

    private final Pattern pattern;
    private final boolean requireMatch;
    private final String  description;

    private RegexValidator(Pattern pattern, boolean requireMatch, String description) {
        this.pattern      = pattern;
        this.requireMatch = requireMatch;
        this.description  = description;
    }

    /** Rejects the payload if it does NOT match {@code regex}. */
    public static RegexValidator matching(String regex) {
        return new RegexValidator(Pattern.compile(regex, Pattern.DOTALL), true, regex);
    }

    /** Rejects the payload if it DOES match {@code regex}. */
    public static RegexValidator notMatching(String regex) {
        return new RegexValidator(Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
                false, regex);
    }

    /** Same as {@link #matching(String)} with an explicit description for rejection messages. */
    public static RegexValidator matching(String regex, String description) {
        return new RegexValidator(Pattern.compile(regex, Pattern.DOTALL), true, description);
    }

    /** Same as {@link #notMatching(String)} with an explicit description for rejection messages. */
    public static RegexValidator notMatching(String regex, String description) {
        return new RegexValidator(Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
                false, description);
    }

    @Override
    public ProcessingResult process(String payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        boolean matched = pattern.matcher(payload).find();
        if (requireMatch && !matched) {
            return ProcessingResult.reject(
                    "Payload does not match required pattern [" + description + "]");
        }
        if (!requireMatch && matched) {
            return ProcessingResult.reject(
                    "Payload matches forbidden pattern [" + description + "]");
        }
        return ProcessingResult.pass(payload);
    }
}
