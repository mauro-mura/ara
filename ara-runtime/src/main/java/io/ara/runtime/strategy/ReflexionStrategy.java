package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmRouter;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.agent.AgentInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reflexion execution strategy (Shinn et al., 2023 — arXiv:2303.11366).
 *
 * <p>Wraps a delegate {@link ExecutionStrategy} and adds a verbal reinforcement loop:
 * when the delegate fails, the agent generates a self-critique (reflection) via a
 * dedicated LLM call, persists it in episodic memory, and retries with all accumulated
 * reflections injected into the working-memory context.
 *
 * <p>Execution loop:
 * <ol>
 *   <li>Run the delegate strategy.</li>
 *   <li>If success → return immediately.</li>
 *   <li>If failed and {@code reflections < maxReflections}: call the LLM to generate a
 *       verbal reflection that diagnoses the root cause and proposes a different approach.</li>
 *   <li>Reset working memory, re-seed with system prompt + recalled context + all
 *       reflections so far + original task input.</li>
 *   <li>Retry from step 1.</li>
 * </ol>
 *
 * <p>Configure via:
 * <pre>{@code
 * AgentConfig.defaults()
 *     .plannerStrategy("reflexion")
 *     .maxReflections(3)
 *     .build()
 * }</pre>
 *
 * <p>The delegate strategy is injected at construction time. In {@link
 * AraRuntime}, the default delegate is {@link ReactStrategy}.
 */
public final class ReflexionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReflexionStrategy.class);

    static final String DEFAULT_REFLECTION_PROMPT =
            "You attempted the following task but did not succeed.\n\n"
            + "Task: {task}\n\n"
            + "Failure reason: {failure}\n\n"
            + "Previous reflections:\n{prior_reflections}\n\n"
            + "Analyse what went wrong and produce a concise reflection (3-5 sentences) that:\n"
            + "1. Identifies the root cause of the failure.\n"
            + "2. Describes specifically what you should do differently on the next attempt.\n"
            + "3. Does NOT repeat the same approach that failed.\n\n"
            + "Reflection:";

    private static final String REFLECTION_SYSTEM =
            "You are a reflective agent. Analyse failures and produce actionable self-critique.";

    private final ExecutionStrategy delegate;
    private final LlmRouter         reflectionRouter;

    /**
     * Creates a new {@code ReflexionStrategy} wrapping the given delegate.
     *
     * <p>{@code StrategyConfig.Reflexion#reflectionProvider()} has no effect with this
     * constructor — there is no router to resolve a named provider against, so every
     * reflection call falls back to the agent's own model, same as before this field
     * existed. Use {@link #ReflexionStrategy(ExecutionStrategy, LlmRouter)} to honor it.
     *
     * @param delegate the inner strategy to run on each attempt; typically {@link ReactStrategy}
     */
    public ReflexionStrategy(ExecutionStrategy delegate) {
        this(delegate, null);
    }

    /**
     * Creates a new {@code ReflexionStrategy} wrapping the given delegate, capable of
     * routing the reflection call to a different LLM provider than the agent's own model.
     *
     * <p>When {@code StrategyConfig.Reflexion#reflectionProvider()} is non-blank, the
     * reflection call is resolved via {@code reflectionRouter.select(LlmConfig.of(providerId), ctx)}
     * instead of reusing the {@code llm} passed to {@link #execute}. This matters because
     * self-critique and other-critique are not the same skill: models are markedly better
     * at catching mistakes in someone else's output than in their own (see "The
     * Self-Correction Illusion: LLMs Correct Others but Not Themselves", 2026) — routing
     * the critique to a different model (or simply a different profile) is a cheap way to
     * approximate an external critic instead of relying on pure same-model self-reflection.
     *
     * @param delegate         the inner strategy to run on each attempt; typically {@link ReactStrategy}
     * @param reflectionRouter resolves the reflection-provider id to an {@link LlmClient};
     *                         {@code null} disables provider routing (same as the single-arg
     *                         constructor) — every reflection call then uses {@code llm}
     */
    public ReflexionStrategy(ExecutionStrategy delegate, LlmRouter reflectionRouter) {
        this.delegate         = Objects.requireNonNull(delegate, "delegate must not be null");
        this.reflectionRouter = reflectionRouter;
    }

    @Override
    public String strategyName() {
        return "reflexion";
    }

    @Override
    public ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config
    ) {
        Objects.requireNonNull(task,   "task must not be null");
        Objects.requireNonNull(llm,    "llm must not be null");
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(tools,  "tools must not be null");
        Objects.requireNonNull(config, "config must not be null");

        StrategyConfig.Reflexion rc = (config.strategyConfig() instanceof StrategyConfig.Reflexion r)
                ? r : StrategyConfig.Reflexion.defaults();
        int    maxReflections = rc.maxReflections();
        String promptTemplate = rc.reflectionPrompt() != null
                ? rc.reflectionPrompt()
                : DEFAULT_REFLECTION_PROMPT;

        LlmCallContext ctx = LlmCallContext.of(config, task);

        // Snapshot initial working-memory state before the delegate writes to it.
        // We need this to re-seed memory cleanly on each retry.
        String       systemPrompt   = extractSystemPrompt(memory);
        List<String> recalledCtx    = extractRecalledContext(memory);

        List<String> priorReflections = new ArrayList<>();
        int totalIterations = 0;
        int totalPromptTokens = 0;
        int totalOutputTokens = 0;

        for (int attempt = 0; attempt <= maxReflections; attempt++) {
            log.debug("Reflexion attempt {}/{} for task [{}]",
                    attempt + 1, maxReflections + 1, task.taskId());

            ExecutionResult result = delegate.execute(task, llm, memory, tools, config);
            totalIterations   += result.iterationsDone();
            totalPromptTokens += result.promptTokens();
            totalOutputTokens += result.outputTokens();

            // Cooperative cancellation: AgentInstance.terminate(session) interrupts this
            // thread. Abort immediately — do NOT generate a reflection or retry, since a
            // cancelled attempt is not a genuine failure to learn from.
            if (Thread.currentThread().isInterrupted()) {
                log.debug("Reflexion: cancelled on attempt {} for task [{}]", attempt + 1, task.taskId());
                return ExecutionResult.failure("Cancelled", totalIterations, totalPromptTokens, totalOutputTokens, result.steps());
            }

            if (result.isSuccess()) {
                log.debug("Reflexion: delegate succeeded on attempt {} for task [{}]",
                        attempt + 1, task.taskId());
                return ExecutionResult.success(result.output(), totalIterations, totalPromptTokens, totalOutputTokens, result.steps());
            }

            if (attempt >= maxReflections) {
                log.warn("Reflexion: max reflections ({}) reached for task [{}]. Last failure: {}",
                        maxReflections, task.taskId(), result.failureReason());
                return ExecutionResult.failure(
                        "Max reflections (%d) reached. Last failure: %s"
                                .formatted(maxReflections, result.failureReason()),
                        totalIterations, totalPromptTokens, totalOutputTokens, result.steps());
            }

            // ── Generate reflection ────────────────────────────────────────────
            Reflection reflection = generateReflection(
                    task, result.failureReason(), priorReflections, promptTemplate, llm, ctx,
                    rc.reflectionProvider());
            // The reflection is an LLM call like any other: its usage must land in the
            // totals, or the cost of every retry cycle is under-reported to the caller
            // (and invisible to any downstream cost accounting).
            totalPromptTokens += reflection.promptTokens();
            totalOutputTokens += reflection.outputTokens();
            log.debug("Reflexion [attempt={}, task={}]: {}",
                    attempt + 1, task.taskId(),
                    reflection.text().length() > 200
                            ? reflection.text().substring(0, 200) + "…" : reflection.text());

            priorReflections.add(reflection.text());

            // ── Reset working memory for next attempt ──────────────────────────
            memory.clearWorkingMemory();
            memory.appendToWorkingMemory("system", systemPrompt);
            for (String recalledEntry : recalledCtx) {
                memory.appendToWorkingMemory("system", recalledEntry);
            }
            // All reflections injected as a system block so each retry learns from all prior attempts
            StringBuilder reflBlock = new StringBuilder(
                    "REFLEXION — lessons learned from previous failed attempt(s):\n");
            for (int i = 0; i < priorReflections.size(); i++) {
                reflBlock.append("Attempt ").append(i + 1).append(": ")
                         .append(priorReflections.get(i)).append("\n");
            }
            memory.appendToWorkingMemory("system", reflBlock.toString().strip());
            memory.appendToWorkingMemory("user", task.input());
        }

        // Unreachable: the loop always returns inside the body
        return ExecutionResult.failure("Reflexion loop exited unexpectedly", totalIterations, totalPromptTokens, totalOutputTokens);
    }

    // ── Reflection generation ─────────────────────────────────────────────────

    /**
     * A generated reflection together with the token usage of the LLM call that produced
     * it. Usage is carried alongside the text — not discarded — because the caller folds
     * it into the execution totals; a blank-but-billed completion (the fallback-text
     * path) still reports the tokens it consumed, while a thrown call reports zero.
     */
    private record Reflection(String text, int promptTokens, int outputTokens) {}

    private Reflection generateReflection(
            AgentTask task,
            String failureReason,
            List<String> priorReflections,
            String promptTemplate,
            LlmClient llm,
            LlmCallContext ctx,
            String reflectionProvider) {

        String priorText = priorReflections.isEmpty()
                ? "(none)"
                : priorReflections.stream()
                        .map(r -> "- " + r)
                        .collect(Collectors.joining("\n"));

        String prompt = promptTemplate
                .replace("{task}",              task.input())
                .replace("{failure}",           failureReason != null ? failureReason : "(unknown)")
                .replace("{prior_reflections}", priorText);

        List<LlmMessage> messages = List.of(
                new LlmMessage("system", REFLECTION_SYSTEM),
                new LlmMessage("user",   prompt)
        );

        LlmClient reflectionLlm = resolveReflectionLlm(llm, ctx, reflectionProvider, task.taskId());

        try {
            LlmCompletion completion = reflectionLlm.complete(messages, ctx);
            String text = completion.text();
            String reflectionText = (text != null && !text.isBlank())
                    ? text.strip()
                    : fallbackReflection(failureReason);
            return new Reflection(reflectionText, completion.promptTokens(), completion.outputTokens());
        } catch (Exception e) {
            log.warn("Reflection generation failed for task [{}]: {}", task.taskId(), e.getMessage());
            return new Reflection(fallbackReflection(failureReason), 0, 0);
        }
    }

    /**
     * Resolves the {@link LlmClient} to use for the reflection call: {@code reflectionProvider}
     * routed through {@link #reflectionRouter} when both are available, otherwise {@code fallback}
     * (the agent's own model) — preserving pre-existing behavior when no router was wired in
     * (see the single-arg constructor) or no provider override was configured.
     */
    private LlmClient resolveReflectionLlm(LlmClient fallback, LlmCallContext ctx,
                                            String reflectionProvider, String taskId) {
        if (reflectionRouter == null || reflectionProvider == null || reflectionProvider.isBlank()) {
            return fallback;
        }
        try {
            return reflectionRouter.select(LlmConfig.of(reflectionProvider), ctx);
        } catch (Exception e) {
            log.warn("Reflexion: failed to resolve reflectionProvider '{}' for task [{}] — "
                    + "falling back to the agent's own model: {}",
                    reflectionProvider, taskId, e.getMessage());
            return fallback;
        }
    }

    private static String fallbackReflection(String failureReason) {
        return "The previous attempt failed (%s). Try a completely different approach."
                .formatted(failureReason != null ? failureReason : "unknown reason");
    }

    // ── Memory helpers ────────────────────────────────────────────────────────

    private static String extractSystemPrompt(MemoryManager memory) {
        List<MemoryEntry> entries = memory.workingMemory();
        if (!entries.isEmpty() && "system".equals(entries.get(0).role())) {
            return entries.get(0).content();
        }
        return "";
    }

    /**
     * Returns any "system" messages injected between the first system prompt and the
     * first "user" message — typically recalled episodic context injected by
     * {@link AgentInstance} before calling the strategy.
     * These are re-injected on each retry so contextual recall is not lost.
     */
    private static List<String> extractRecalledContext(MemoryManager memory) {
        List<MemoryEntry> entries = memory.workingMemory();
        List<String> recalled = new ArrayList<>();
        // Skip index 0 (main system prompt). Collect "system" entries until first "user".
        for (int i = 1; i < entries.size(); i++) {
            MemoryEntry e = entries.get(i);
            if ("user".equals(e.role())) break;
            if ("system".equals(e.role())) recalled.add(e.content());
        }
        return recalled;
    }
}
