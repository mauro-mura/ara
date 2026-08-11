package io.ara.core.agent.processor;

/**
 * Base functional interface for deterministic, LLM-free payload transformations.
 *
 * <p>{@link InputProcessor} and {@link OutputProcessor} extend this interface to
 * preserve their semantic distinction at the declaration site ({@link io.ara.core.agent.AgentContract})
 * without duplicating the method signature.
 */
@FunctionalInterface
public interface Processor {

    /**
     * @param content the payload to process (raw string, JSON, free text)
     * @return {@link ProcessingResult#pass(String)} with the (possibly transformed) content,
     *         or {@link ProcessingResult#reject(String)} with a human-readable reason
     */
    ProcessingResult process(String content);
}
