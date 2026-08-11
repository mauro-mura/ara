package io.ara.runtime.contract;

import io.ara.core.agent.AgentInstanceContext;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.processor.PromptShaper;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PromptShaper} that resolves {@code {key}} placeholders in the system prompt
 * with values from {@link AgentTask#runContext()}, with optional declarative defaults.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>{@code task.runContext().promptVars()} — runtime values injected by the caller</li>
 *   <li>Defaults declared in the template</li>
 *   <li>Unresolved placeholders — left unchanged by default;
 *       {@link #strict()} throws {@link IllegalStateException} instead</li>
 * </ol>
 *
 * <p>Placeholders are delimited by single braces ({@code {key}}) by default. Use
 * {@link #delimiters(String, String)} to switch to a different pair (e.g. {@code {{key}}})
 * when the prompt already contains literal single braces — a JSON example block, say —
 * that would otherwise be mistaken for placeholders.
 *
 * <pre>{@code
 * // Uses only task.runContext().promptVars() — no defaults
 * PromptTemplate.instance()
 *
 * // With declarative defaults (overridable at runtime via task.runContext().promptVars())
 * PromptTemplate.withDefaults(Map.of("lang", "italiano", "env", "production"))
 *
 * // Live per-agent instance parameters (ADR-036) — reads the current value of ctx on
 * // every shape() call, unlike withDefaults() which freezes the map at construction time
 * PromptTemplate.withInstanceContext(ctx)
 *
 * // Strict mode: throws if a placeholder cannot be resolved
 * PromptTemplate.withDefaults(Map.of("lang", "italiano")).strict()
 *
 * // Double-brace delimiters, e.g. for prompts with literal JSON examples
 * PromptTemplate.instance().strict().delimiters("{{", "}}")
 * }</pre>
 *
 * <p>Must be added to {@code AgentContract.promptShapers} before
 * {@code AgentContract.outputSchema} is applied — the framework guarantees this order.
 */
public final class PromptTemplate implements PromptShaper {

    private static final String  DEFAULT_OPEN        = "{";
    private static final String  DEFAULT_CLOSE       = "}";
    private static final Pattern DEFAULT_PLACEHOLDER = placeholderPattern(DEFAULT_OPEN, DEFAULT_CLOSE);

    private final Function<String, String> resolver;
    private final boolean                   strict;
    private final String                    open;
    private final String                    close;
    private final Pattern                   placeholderPattern;

    private PromptTemplate(Function<String, String> resolver, boolean strict,
                            String open, String close, Pattern placeholderPattern) {
        this.resolver           = resolver;
        this.strict             = strict;
        this.open               = open;
        this.close              = close;
        this.placeholderPattern = placeholderPattern;
    }

    /** Resolves placeholders from {@code task.runContext().promptVars()} only — no defaults. */
    public static PromptTemplate instance() {
        return new PromptTemplate(key -> null, false, DEFAULT_OPEN, DEFAULT_CLOSE, DEFAULT_PLACEHOLDER);
    }

    /**
     * Resolves placeholders from {@code task.runContext().promptVars()}, falling back to
     * {@code defaults} when the key is absent from the context. {@code defaults} is
     * copied and frozen at construction time — for a live, per-agent instance context
     * that reflects updates without rebuilding the template, use {@link
     * #withInstanceContext(AgentInstanceContext)}.
     */
    public static PromptTemplate withDefaults(Map<String, String> defaults) {
        Objects.requireNonNull(defaults, "defaults must not be null");
        Map<String, String> frozen = Map.copyOf(defaults);
        return new PromptTemplate(frozen::get, false, DEFAULT_OPEN, DEFAULT_CLOSE, DEFAULT_PLACEHOLDER);
    }

    /**
     * Resolves placeholders from {@code task.runContext().promptVars()}, falling back to {@code ctx}
     * (ADR-036) when the key is absent from the context. Unlike {@link
     * #withDefaults(Map)}, {@code ctx} is read live on every {@link #shape} call — no
     * value is frozen at construction time, so updates to the backing {@code
     * InstanceContextStore} are reflected on the very next execution without rebuilding
     * this template or the {@code AgentContract} it belongs to.
     */
    public static PromptTemplate withInstanceContext(AgentInstanceContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        return new PromptTemplate(ctx::get, false, DEFAULT_OPEN, DEFAULT_CLOSE, DEFAULT_PLACEHOLDER);
    }

    /**
     * Returns a copy of this template in strict mode: an unresolved placeholder
     * causes {@link IllegalStateException} instead of being left unchanged.
     */
    public PromptTemplate strict() {
        return new PromptTemplate(resolver, true, open, close, placeholderPattern);
    }

    /**
     * Returns a copy of this template that recognizes placeholders delimited by
     * {@code open}/{@code close} instead of the default single braces — e.g.
     * {@code delimiters("{{", "}}")} for {@code {{key}}}. Useful when the prompt already
     * contains literal single braces (a JSON example block, say) that would otherwise be
     * mistaken for placeholders.
     *
     * @param open  the opening delimiter; must not be blank
     * @param close the closing delimiter; must not be blank
     */
    public PromptTemplate delimiters(String open, String close) {
        Objects.requireNonNull(open, "open must not be null");
        Objects.requireNonNull(close, "close must not be null");
        if (open.isBlank() || close.isBlank()) {
            throw new IllegalArgumentException("delimiters must not be blank");
        }
        return new PromptTemplate(resolver, strict, open, close, placeholderPattern(open, close));
    }

    @Override
    public String shape(String systemPrompt, AgentTask task) {
        Matcher matcher = placeholderPattern.matcher(systemPrompt);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key   = matcher.group(1);
            String value = task.runContext().promptVar(key);
            if (value == null) value = resolver.apply(key);
            if (value != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else if (strict) {
                throw new IllegalStateException(
                        "PromptTemplate: unresolved placeholder " + open + key + close + " — " +
                        "add it to task.runContext().promptVars() or to the template defaults.");
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Builds the placeholder regex for a given delimiter pair: {@code open\s*(\w+)\s*close}.
     * Whitespace around the key is tolerated, so {@code {{ key }}} and {@code {{key}}}
     * are equivalent.
     */
    private static Pattern placeholderPattern(String open, String close) {
        return Pattern.compile(Pattern.quote(open) + "\\s*(\\w+)\\s*" + Pattern.quote(close));
    }
}
