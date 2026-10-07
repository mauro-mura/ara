package io.ara.core.llm;

import java.util.Locale;

/**
 * How hard a model that reasons is asked to reason, for the providers that have such a dial.
 *
 * <p>Not every provider has one, and none of them share a scale beyond the three words below: OpenAI
 * takes {@code "low"}, {@code "medium"} or {@code "high"}; Anthropic takes a budget of tokens
 * instead ({@link LlmProfile#thinkingBudgetTokens()}); Ollama can only turn thinking on or off.
 * There is deliberately no table converting one into another, because it would be a claim about
 * models this library does not control. An adapter that cannot apply an effort rejects it.
 *
 * <p><b>Known limit: a server that accepts the parameter and ignores it.</b> The OpenAI adapter
 * sends {@code reasoning_effort}. Real OpenAI applies it, but an OpenAI-compatible local server may
 * not: LM Studio serving gpt-oss-20b returned identical token counts for {@code low} and
 * {@code high}, in every form of the parameter that was tried. Nothing in the response says the
 * value was ignored, so the adapter cannot tell and does not reject it. For gpt-oss the model's
 * own dial is a line {@code Reasoning: low|medium|high} in the system prompt, which did change the
 * reasoning length on that server; it is not set by this option. With another server, check
 * that the effort changes the reasoning tokens before relying on it.
 */
public enum ReasoningEffort {
    LOW, MEDIUM, HIGH;

    /** The lower-case word a provider's API expects ({@code "low"}, {@code "medium"}, {@code "high"}). */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
