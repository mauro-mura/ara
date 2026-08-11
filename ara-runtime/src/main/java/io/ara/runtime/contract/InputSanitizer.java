package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rejects input that contains prompt-injection or jailbreak patterns before it
 * reaches the LLM.
 *
 * <p>The built-in pattern set covers the most common English and Italian injection
 * attempts. Use {@link #withExtra(String...)} to add domain-specific patterns on top
 * of the built-in ones, or {@link #custom(String...)} to replace them entirely.
 *
 * <pre>{@code
 * // Default built-in guards
 * InputSanitizer.instance()
 *
 * // Built-in guards plus project-specific forbidden phrases
 * InputSanitizer.withExtra("bypass security", "override policy")
 *
 * // Only custom patterns (no built-ins)
 * InputSanitizer.custom("my-forbidden-phrase")
 * }</pre>
 */
public final class InputSanitizer implements InputProcessor {

    private static final List<Pattern> BUILTIN = List.of(
            compile("ignore (previous|all|prior|above) instructions?"),
            compile("disregard (previous|all|prior|above) instructions?"),
            compile("forget (everything|all|what) (you|i) (said|told|wrote)"),
            compile("you are now (in |)(developer|jailbreak|dan|evil|unrestricted) mode"),
            compile("(pretend|act|behave) (as if|like) you (are|have no|don't have)"),
            compile("(system|assistant|user)\\s*:\\s*(ignore|bypass|override)"),
            compile("\\[INST\\].*\\[/INST\\]"),   // Llama2 injection marker
            compile("<\\|?(im_start|im_end|system|user|assistant)\\|?>"),  // ChatML injection
            // Italian variants
            compile("ignora (le |le istruzioni |tutto |tutti |)(precedenti|precedente) (istruzioni?|comandi?)"),
            compile("dimentica (tutto|tutto ciò che|le istruzioni)")
    );

    private static final InputSanitizer INSTANCE = new InputSanitizer(BUILTIN);

    private final List<Pattern> patterns;

    private InputSanitizer(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    /** Returns the shared instance with built-in patterns only. */
    public static InputSanitizer instance() { return INSTANCE; }

    /** Built-in patterns plus the supplied extra patterns. */
    public static InputSanitizer withExtra(String... extra) {
        List<Pattern> combined = new java.util.ArrayList<>(BUILTIN);
        for (String p : extra) combined.add(compile(p));
        return new InputSanitizer(List.copyOf(combined));
    }

    /** Only the supplied patterns — no built-ins. */
    public static InputSanitizer custom(String... patterns) {
        List<Pattern> compiled = new java.util.ArrayList<>();
        for (String p : patterns) compiled.add(compile(p));
        return new InputSanitizer(List.copyOf(compiled));
    }

    @Override
    public ProcessingResult process(String input) {
        if (input == null) return ProcessingResult.pass("");
        for (Pattern p : patterns) {
            if (p.matcher(input).find()) {
                return ProcessingResult.reject(
                        "Input rejected: possible prompt injection detected");
            }
        }
        return ProcessingResult.pass(input);
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}
