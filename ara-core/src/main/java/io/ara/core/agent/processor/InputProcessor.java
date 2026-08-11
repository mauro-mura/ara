package io.ara.core.agent.processor;

/**
 * Deterministic component applied to an agent's input before {@code execute()}.
 *
 * <p>Implementations must not invoke any LLM. They may validate, transform,
 * enrich, or reject the incoming payload.
 *
 * <p>Declared in {@link io.ara.core.agent.AgentContract#inputProcessors()} and applied in order
 * by {@code ContractEnforcingAgent}.
 */
@FunctionalInterface
public interface InputProcessor extends Processor {}
