package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ContractViolation;
import io.ara.core.agent.ContractViolation.Phase;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.MediaValidator;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.agent.processor.PromptShaper;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmExecutionHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Applies an {@link AgentContract} around an {@link AraAgent} execution.
 *
 * <p>Extracted from {@link ContractEnforcingAgent} so that the enforcement order
 * (input → shaping → execute → output, then repair while the output is rejected and
 * {@link AgentContract#outputRepairAttempts()} allows) lives in one place. Adding a new
 * contract phase requires changing only this class.
 */
final class ContractEnforcer {

    private static final Logger log = LoggerFactory.getLogger(ContractEnforcer.class);

    /** Cap on the rejected answer quoted back in a repair attempt's input. */
    static final int MAX_REJECTED_ANSWER_CHARS = 4_000;

    private ContractEnforcer() {}

    static AgentResponse apply(AgentContract contract, AraAgent inner,
                               AgentTask task, Instant start) {
        // ── 1. Input processing ───────────────────────────────────────────────
        Processed<String> processedInput = runChain(
                task.input(), contract.inputProcessors(), InputProcessor::process);
        if (processedInput.rejected()) {
            return violation(task, inner, start, Phase.INPUT, processedInput.rejectionReason(), processedInput.issues());
        }

        // ── 1b. Media validation ──────────────────────────────────────────────
        // Separate from the input chain because a processor only sees the input string and
        // cannot look at an attachment. Runs before shaping and before execution, so a task
        // over its media limits never reaches the model and never spends a token.
        for (MediaValidator validator : contract.mediaValidators()) {
            Optional<String> rejection = validator.validate(task.media());
            if (rejection.isPresent()) {
                return violation(task, inner, start, Phase.MEDIA, rejection.get(), List.of());
            }
        }

        // ── 2+3. Prompt shaping + outputSchema (ADR-014) ──────────────────────
        AgentTask transformedTask = shape(contract, inner, task.withInput(processedInput.value()));

        // ── 4. Delegate ───────────────────────────────────────────────────────
        AgentResponse response = inner.execute(transformedTask);
        if (!response.isSuccess()) return response;

        // ── 5. Output processing, with optional repair ────────────────────────
        Processed<String> processedOutput = runChain(
                response.content(), contract.outputProcessors(), OutputProcessor::process);
        AgentResponse total = response;
        for (int attempt = 1; processedOutput.rejected() && attempt <= contract.outputRepairAttempts(); attempt++) {
            log.debug("Agent [{}] task [{}]: output rejected ({}), repair attempt {}/{}",
                    inner.agentId().value(), task.taskId(), processedOutput.rejectionReason(),
                    attempt, contract.outputRepairAttempts());
            AgentResponse retry = inner.execute(transformedTask.withInput(repairInput(
                    transformedTask.input(), response.content(), processedOutput.rejectionReason())));
            total = accumulate(total, retry, start);
            if (!retry.isSuccess()) return total;
            response = retry;
            processedOutput = runChain(
                    response.content(), contract.outputProcessors(), OutputProcessor::process);
        }
        if (processedOutput.rejected()) {
            return outputViolation(task, inner, total, start,
                    processedOutput.rejectionReason(), processedOutput.issues());
        }

        return total.withContent(processedOutput.value());
    }

    /**
     * The input of a repair attempt: the original request, followed by the rejected answer
     * and the reason the output chain gave. The original input comes first and unchanged, so
     * a strategy or shaper that keys on it still sees it; the rejected answer is capped at
     * {@value #MAX_REJECTED_ANSWER_CHARS} characters, since a runaway answer would otherwise
     * be paid for twice. When the task carries a session, the agent records this exchange in
     * the session's history like any other turn.
     */
    static String repairInput(String originalInput, String rejectedAnswer, String reason) {
        String answer = rejectedAnswer.length() > MAX_REJECTED_ANSWER_CHARS
                ? rejectedAnswer.substring(0, MAX_REJECTED_ANSWER_CHARS) + "\n[… truncated]"
                : rejectedAnswer;
        return originalInput
                + "\n\n---\n"
                + "Your previous answer was rejected by the output contract and must be corrected.\n"
                + "Rejection: " + reason + "\n"
                + "Previous answer:\n" + answer + "\n"
                + "---\n"
                + "Answer the original request again, fixing every problem listed in the rejection. "
                + "Reply with the corrected answer only.";
    }

    /**
     * {@code next} — its content, state, failure reason and provider — carrying the usage of
     * every attempt so far: iterations, tokens, cost and steps add up, and the elapsed time
     * runs from the start of the first attempt. A repaired task is billed for all of its
     * attempts, not only for the one that passed.
     */
    private static AgentResponse accumulate(AgentResponse previous, AgentResponse next, Instant start) {
        List<ExecutionStep> steps = new ArrayList<>(previous.steps());
        steps.addAll(next.steps());
        return new AgentResponse(
                next.taskId(), next.agentId(), next.content(), next.finalState(),
                previous.iterationsUsed() + next.iterationsUsed(),
                previous.inputTokens() + next.inputTokens(),
                previous.outputTokens() + next.outputTokens(),
                addCost(previous.estimatedCost(), next.estimatedCost()),
                Duration.between(start, Instant.now()),
                next.failureReason(), next.completedAt(), steps, next.llmProvider(), next.violation());
    }

    /**
     * Sums two costs. A failed response carries {@code Money.ZERO_EUR} whatever the agent's
     * currency, so a zero on either side is skipped rather than allowed to trip
     * {@link Money#plus}'s currency check; two non-zero amounts in different currencies cannot
     * be summed without a rate, and the later one is kept.
     */
    private static Money addCost(Money a, Money b) {
        if (a.amount().signum() == 0) return b;
        if (b.amount().signum() == 0) return a;
        return a.currency().equals(b.currency()) ? a.plus(b) : b;
    }

    /**
     * An output rejection that survived every repair attempt (or had none): the same failure
     * reason {@link #violation} gives, but keeping the usage the attempts consumed, so the
     * rejected work still shows up in token and cost accounting.
     */
    private static AgentResponse outputViolation(AgentTask task, AraAgent inner, AgentResponse usage,
                                                 Instant start, String reason,
                                                 List<ProcessingResult.Issue> issues) {
        return new AgentResponse(
                task.taskId(), inner.agentId(), "", AgentState.FAILED,
                usage.iterationsUsed(), usage.inputTokens(), usage.outputTokens(), usage.estimatedCost(),
                Duration.between(start, Instant.now()),
                "Contract output violation: " + reason, Instant.now(), usage.steps(), usage.llmProvider(),
                new ContractViolation(Phase.OUTPUT, issues));
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
                case ProcessingResult.Reject reject -> {
                    return Processed.rejected(reject.reason(), reject.issues());
                }
            }
        }
        return Processed.passed(current);
    }

    private static AgentResponse violation(AgentTask task, AraAgent inner, Instant start,
                                           Phase phase, String reason, List<ProcessingResult.Issue> issues) {
        return AgentResponse.failure(
                task.taskId(), inner.agentId(),
                "Contract " + phase.name().toLowerCase(Locale.ROOT) + " violation: " + reason,
                Duration.between(start, Instant.now()))
                .withViolation(new ContractViolation(phase, issues));
    }

    /**
     * Outcome of a processor chain: either the threaded value, or the reason it was rejected
     * and the itemised issues behind it (empty when the processor only gave a reason).
     */
    private record Processed<T>(T value, String rejectionReason, List<ProcessingResult.Issue> issues) {
        static <T> Processed<T> passed(T value) {
            return new Processed<>(value, null, List.of());
        }
        static <T> Processed<T> rejected(String reason, List<ProcessingResult.Issue> issues) {
            return new Processed<>(null, reason, issues);
        }
        boolean rejected() { return rejectionReason != null; }
    }
}
