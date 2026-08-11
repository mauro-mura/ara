package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.agent.processor.PromptShaper;
import io.ara.core.llm.LlmExecutionHints;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Applies an {@link AgentContract} around an {@link AraAgent} execution.
 *
 * <p>Extracted from {@link ContractEnforcingAgent} so that the enforcement order
 * (input → shaping → execute → output) lives in one place. Adding a new contract
 * phase requires changing only this class.
 */
final class ContractEnforcer {

    private ContractEnforcer() {}

    static AgentResponse apply(AgentContract contract, AraAgent inner,
                               AgentTask task, Instant start) {
        // ── 1. Input processing ───────────────────────────────────────────────
        Processed<String> processedInput = runChain(
                task.input(), contract.inputProcessors(), InputProcessor::process);
        if (processedInput.rejected()) {
            return violation(task, inner, start, "input", processedInput.rejectionReason());
        }

        // ── 2+3. Prompt shaping + outputSchema (ADR-014) ──────────────────────
        AgentTask transformedTask = shape(contract, inner, task.withInput(processedInput.value()));

        // ── 4. Delegate ───────────────────────────────────────────────────────
        AgentResponse response = inner.execute(transformedTask);
        if (!response.isSuccess()) return response;

        // ── 5. Output processing ──────────────────────────────────────────────
        Processed<String> processedOutput = runChain(
                response.content(), contract.outputProcessors(), OutputProcessor::process);
        if (processedOutput.rejected()) {
            return violation(task, inner, start, "output", processedOutput.rejectionReason());
        }

        return response.withContent(processedOutput.value());
    }

    /**
     * Applies the {@code PromptShaper} chain and any declared {@code outputSchema},
     * returning the task to delegate. When the contract declares neither, {@code task} is
     * returned untouched — no config read, no context entry, exactly the pre-ADR-014 path.
     */
    private static AgentTask shape(AgentContract contract, AraAgent inner, AgentTask task) {
        if (contract.promptShapers().isEmpty() && contract.outputSchema() == null) {
            return task;
        }

        AgentConfig cfg = inner.config();
        String systemPrompt = (cfg != null && cfg.systemPrompt() != null) ? cfg.systemPrompt() : "";

        for (PromptShaper shaper : contract.promptShapers()) {
            systemPrompt = shaper.shape(systemPrompt, task);
        }

        AgentTask shaped = task;
        if (contract.outputSchema() != null) {
            LlmExecutionHints base = shaped.hints() != null ? shaped.hints() : LlmExecutionHints.empty();
            shaped = shaped.withHints(base.withOutputSchema(
                    contract.outputSchema().jsonSchema(), null, false));

            if (cfg == null || !cfg.nativeJsonSchema()) {
                systemPrompt = OutputFormatEnforcer.apply(systemPrompt, contract.outputSchema());
            }
        }

        return shaped.withContextEntry(AgentInstance.CTX_SYSTEM_PROMPT, systemPrompt);
    }

    /**
     * Threads {@code value} through {@code processors}, stopping at the first rejection.
     * Shared by the input and output chains, which differ only in their processor type —
     * previously two copies of the same {@code for} + {@code switch}, each with its own
     * copy of the early-return construction.
     */
    private static <P> Processed<String> runChain(String value, List<P> processors,
                                                  BiFunction<P, String, ProcessingResult> invoker) {
        String current = value;
        for (P processor : processors) {
            switch (invoker.apply(processor, current)) {
                case ProcessingResult.Pass pass     -> current = pass.value();
                case ProcessingResult.Reject reject -> { return Processed.rejected(reject.reason()); }
            }
        }
        return Processed.passed(current);
    }

    private static AgentResponse violation(AgentTask task, AraAgent inner, Instant start,
                                           String phase, String reason) {
        return AgentResponse.failure(
                task.taskId(), inner.agentId(),
                "Contract " + phase + " violation: " + reason,
                Duration.between(start, Instant.now()));
    }

    /** Outcome of a processor chain: either the threaded value, or the reason it was rejected. */
    private record Processed<T>(T value, String rejectionReason) {
        static <T> Processed<T> passed(T value)         { return new Processed<>(value, null); }
        static <T> Processed<T> rejected(String reason) { return new Processed<>(null, reason); }
        boolean rejected()                              { return rejectionReason != null; }
    }
}
