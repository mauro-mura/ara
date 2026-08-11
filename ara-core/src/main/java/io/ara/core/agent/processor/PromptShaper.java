package io.ara.core.agent.processor;

import io.ara.core.agent.AgentTask;

/**
 * Deterministic component applied to the system prompt before {@code agent.execute()}.
 *
 * <p>Does not invoke an LLM. Each shaper receives the prompt as produced by the
 * previous shaper in the chain (or the base {@link io.ara.core.agent.AgentConfig#systemPrompt()} for
 * the first one) and returns the modified version.
 *
 * <p>Applied by {@code ContractEnforcingAgent} in the
 * order declared in {@link io.ara.core.agent.AgentContract#promptShapers()}, after the
 * {@link InputProcessor} chain and before {@code execute()}.
 *
 * <p>Since this is a {@code @FunctionalInterface}, simple cases can be expressed
 * as lambdas without a dedicated class:
 * <pre>{@code
 * PromptShaper policy = (prompt, task) ->
 *         prompt + "\n\nIMPORTANT: Do not reveal PII in the output.";
 * }</pre>
 */
@FunctionalInterface
public interface PromptShaper {

    /**
     * @param systemPrompt the current system prompt (already shaped by previous shapers)
     * @param task         the current task — input already transformed by InputProcessors
     * @return the modified system prompt; never {@code null}
     */
    String shape(String systemPrompt, AgentTask task);
}
