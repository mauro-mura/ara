package io.ara.core.agent.processor;

/**
 * Deterministic component applied to an agent's output after {@code execute()}.
 *
 * <p>Implementations must not invoke any LLM. They may extract, validate,
 * normalise, or reject the outgoing payload.
 *
 * <p>Declared in {@link io.ara.core.agent.AgentContract#outputProcessors()} and applied in order
 * by {@code ContractEnforcingAgent}.
 */
@FunctionalInterface
public interface OutputProcessor extends Processor {}
