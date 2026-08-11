package io.ara.core.agent;

import java.util.Objects;

/**
 * Typed, strategy-specific configuration companion to {@link AgentConfig}.
 *
 * <p>Replaces the flat nullable fields that were scattered on {@code AgentConfig}
 * (e.g. {@code maxReflections}, {@code replanStrategy}) with a sealed type hierarchy
 * where each permitted type carries exactly the parameters its strategy needs.
 *
 * <p>Permitted to the strategies actually implemented by {@code ExecutionPlanner}'s
 * registered set ({@code ReactStrategy}, {@code ReSpActStrategy}, {@code
 * PlanExecuteStrategy}, {@code ReflexionStrategy}, {@code ReflActStrategy}).
 * Add a new permitted type only alongside a real {@code ExecutionStrategy}
 * implementation — see ADR-001 P9.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Reflexion with 3 retries
 * AgentConfig config = AgentConfig.defaults()
 *     .agentType("code-fixer")
 *     .strategyConfig(new StrategyConfig.Reflexion(3, null, null))
 *     .build();
 *
 * // Plan-execute with on_failure replan, max 6 steps
 * AgentConfig config = AgentConfig.defaults()
 *     .agentType("researcher")
 *     .strategyConfig(new StrategyConfig.PlanExecute("on_failure", 6, 3))
 *     .build();
 *
 * // Simple react (default) — no strategyConfig needed
 * AgentConfig config = AgentConfig.defaults()
 *     .agentType("assistant")
 *     .build();
 * }</pre>
 *
 * <p>Each permitted type exposes a {@link #strategyName()} method that returns the
 * string key used by {@code ExecutionPlanner} to
 * select the strategy. When {@code strategyConfig} is {@code null} on
 * {@code AgentConfig}, the string {@code plannerStrategy} field governs selection
 * (preserved for backward compatibility with REST/persistence callers that pass
 * strategy names as plain strings).
 */
public sealed interface StrategyConfig permits
        StrategyConfig.React,
        StrategyConfig.PlanExecute,
        StrategyConfig.Reflexion,
        StrategyConfig.ReflAct {

    /** Returns the string key used by {@code ExecutionPlanner} to select this strategy. */
    String strategyName();

    // ── built-in strategies ───────────────────────────────────────────────────

    /** Standard ReAct (Reason + Act) loop — the default strategy. No extra config needed. */
    record React() implements StrategyConfig {
        @Override public String strategyName() { return "react"; }
    }

    /**
     * Plan-then-execute strategy (ReWOO-inspired).
     *
     * @param replanPolicy          {@code "never"} or {@code "on_failure"}; default {@code "never"}
     * @param maxPlanSteps          max steps the planner may generate; default 8
     * @param maxStepRoundsPerStep  max Think→Act→Observe rounds per step; default 3
     */
    record PlanExecute(
            String replanPolicy,
            int    maxPlanSteps,
            int    maxStepRoundsPerStep
    ) implements StrategyConfig {

        public PlanExecute {
            Objects.requireNonNull(replanPolicy, "replanPolicy must not be null");
            if (!replanPolicy.equals("never") && !replanPolicy.equals("on_failure"))
                throw new IllegalArgumentException(
                        "replanPolicy must be 'never' or 'on_failure', got: " + replanPolicy);
            if (maxPlanSteps < 1)
                throw new IllegalArgumentException("maxPlanSteps must be >= 1, got: " + maxPlanSteps);
            if (maxStepRoundsPerStep < 1)
                throw new IllegalArgumentException(
                        "maxStepRoundsPerStep must be >= 1, got: " + maxStepRoundsPerStep);
        }

        @Override public String strategyName() { return "plan_execute"; }

        /** Returns a {@code PlanExecute} config with production defaults. */
        public static PlanExecute defaults() { return new PlanExecute("never", 8, 3); }
    }

    /**
     * Reflexion strategy — verbal reinforcement loop.
     *
     * @param maxReflections    max reflection-and-retry cycles; 0 = no reflection (falls back to delegate); default 2
     * @param reflectionPrompt  custom reflection prompt template; {@code null} uses the built-in default
     * @param reflectionProvider optional LLM provider id for the reflection call; {@code null} = same as agent
     */
    record Reflexion(
            int    maxReflections,
            String reflectionPrompt,
            String reflectionProvider
    ) implements StrategyConfig {

        public Reflexion {
            if (maxReflections < 0)
                throw new IllegalArgumentException("maxReflections must be >= 0, got: " + maxReflections);
        }

        @Override public String strategyName() { return "reflexion"; }

        /** Returns a {@code Reflexion} config with production defaults (2 retries, built-in prompt). */
        public static Reflexion defaults() { return new Reflexion(2, null, null); }
    }

    /**
     * ReflAct strategy — a single ReAct loop with in-place, in-loop self-correction.
     *
     * <p>Unlike {@link Reflexion} (a decorator that, on total delegate failure, wipes
     * working memory and restarts the whole episode with a critique of the failed
     * attempt), {@code ReflActStrategy} reflects <em>within</em> one ongoing episode: a
     * failed tool call, or {@code unproductiveStreak} consecutive iterations that
     * neither dispatch a tool nor produce a final answer, triggers a short course-
     * correction that is appended to the same working memory — nothing is reset, the
     * loop simply continues with the correction now in context.
     *
     * @param maxReflections       max in-loop reflections for the whole task; 0 disables
     *                             self-correction entirely (equivalent to plain ReAct);
     *                             default 3
     * @param unproductiveStreak   consecutive no-tool/no-final-answer iterations that
     *                             trigger a reflection; must be >= 1; default 2
     * @param reflectOnToolFailure whether a failed tool call triggers an immediate
     *                             reflection, independent of the unproductive-streak
     *                             counter; default {@code true}
     * @param reflectionProvider   optional LLM provider id for the reflection call
     *                             (dual-model separation: a cheaper/faster model executes,
     *                             a stronger one critiques); {@code null} = same model as
     *                             the main loop
     */
    record ReflAct(
            int     maxReflections,
            int     unproductiveStreak,
            boolean reflectOnToolFailure,
            String  reflectionProvider
    ) implements StrategyConfig {

        public ReflAct {
            if (maxReflections < 0)
                throw new IllegalArgumentException("maxReflections must be >= 0, got: " + maxReflections);
            if (unproductiveStreak < 1)
                throw new IllegalArgumentException("unproductiveStreak must be >= 1, got: " + unproductiveStreak);
        }

        @Override public String strategyName() { return "reflact"; }

        /** Returns a {@code ReflAct} config with production defaults (3 reflections, streak of 2, reflect-on-failure). */
        public static ReflAct defaults() { return new ReflAct(3, 2, true, null); }
    }

    // ── static factory methods ────────────────────────────────────────────────

    static React           react()           { return new React(); }
    static PlanExecute     planExecute()      { return PlanExecute.defaults(); }
    static Reflexion       reflexion()        { return Reflexion.defaults(); }
    static ReflAct         reflact()          { return ReflAct.defaults(); }
}
