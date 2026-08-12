package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.runtime.pipeline.PipelineExecution.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Thin orchestrator that sequences a chain of {@link AraAgent} steps with optional
 * conditional routing.
 *
 * <p>Each step is identified by a name. After each step, an optional router receives a
 * {@link PipelineExecution} — a typed, immutable snapshot of the full execution history —
 * and returns the name of the next step to execute, enabling conditional branching and
 * retry loops.
 *
 * <p>Agents remain unaware of the pipeline: they always receive a plain {@link AgentTask}
 * with the previous step's output as input. Only routers see the execution context.
 * When run via {@link #run(AgentTask)}, every step's task is derived from the same
 * incoming task via {@link AgentTask#withInput}, so {@code attachments()}, {@code
 * context()}, {@code sessionId()}, and {@code hints()} carry through to every step
 * unchanged — only {@code input()} varies from step to step.
 *
 * <p>Validation and transformation logic belongs in each agent's {@link
 * io.ara.core.agent.AgentContract} — the pipeline itself does not touch payloads.
 *
 * <p>Usage:
 * <pre>{@code
 * AgentPipeline pipeline = AgentPipeline.builder()
 *     .step("analyze", analyst)
 *     .step("write",   writer)
 *     .route("analyze", execution ->
 *         execution.lastOutput().length() < 50 ? "analyze" : "write")
 *     .build();
 *
 * PipelineResult result = pipeline.run("Summarise AI news");
 * }</pre>
 */
public final class AgentPipeline {

    private static final Logger log = LoggerFactory.getLogger(AgentPipeline.class);

    private static final int DEFAULT_MAX_STEPS = 20;

    // stepOrder is the correctness-critical source of step order: Map.copyOf does not
    // guarantee iteration order preservation even when the source is a LinkedHashMap,
    // so sequential "advance to the next step" logic must never rely on `steps`' own
    // iteration order — only on this explicit list.
    private final List<String>              stepOrder;
    private final Map<String, PipelineStep> steps;
    private final int                       maxSteps;

    private AgentPipeline(Builder builder) {
        this.stepOrder = List.copyOf(builder.stepOrder);
        this.steps     = Map.copyOf(builder.steps);
        this.maxSteps  = builder.maxSteps;
    }

    /**
     * Runs the pipeline starting from the first declared step.
     *
     * @param initialInput the input string for the first step
     * @return the pipeline result; never {@code null}
     */
    public PipelineResult run(String initialInput) {
        Objects.requireNonNull(initialInput, "initialInput must not be null");
        return run(AgentTask.of(initialInput));
    }

    /**
     * Runs the pipeline starting from the first declared step, using {@code task} as the
     * template for every step's own task: each step receives {@code task.withInput(...)}
     * with the previous step's output, rather than a bare {@code AgentTask.of(...)} — so
     * {@code attachments()}, {@code context()}, {@code sessionId()}, and {@code hints()}
     * survive into every step, not just the initial input string. All steps share
     * {@code task}'s {@code taskId()}, which doubles as a free correlation key across the
     * whole pipeline run in logs/traces.
     *
     * @param task the task whose {@code input()} seeds the first step
     * @return the pipeline result; never {@code null}
     */
    public PipelineResult run(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        Instant start = Instant.now();
        List<String> executed = new ArrayList<>();

        String        currentStep  = stepOrder.get(0);
        AgentResponse lastResponse = null;
        int           stepCount    = 0;
        List<StepResult> history   = new ArrayList<>();

        while (currentStep != null) {
            if (stepCount >= maxSteps) {
                log.warn("Pipeline exceeded maxSteps={}", maxSteps);
                return PipelineResult.failure(
                        "Pipeline exceeded maximum step count of " + maxSteps,
                        lastResponse, executed, elapsed(start));
            }

            PipelineStep step = steps.get(currentStep);
            if (step == null) {
                return PipelineResult.failure(
                        "Unknown step: '" + currentStep + "'",
                        lastResponse, executed, elapsed(start));
            }

            PipelineExecution execution = new PipelineExecution(task, history, stepCount);
            AgentTask stepTask;
            try {
                stepTask = step.buildTask(execution);
            } catch (RuntimeException e) {
                log.warn("Pipeline step [{}] input shaper threw", currentStep, e);
                return PipelineResult.failure(
                        "Step '" + currentStep + "' input shaper threw: " + e,
                        lastResponse, executed, elapsed(start));
            }

            log.debug("Pipeline step [{}] input.len={}", currentStep, stepTask.input().length());
            Instant stepStart = Instant.now();
            lastResponse = step.agent().execute(stepTask);
            executed.add(currentStep);
            stepCount++;

            if (!lastResponse.isSuccess()) {
                log.warn("Pipeline step [{}] failed: {}", currentStep, lastResponse.failureReason());
                return PipelineResult.failure(
                        "Step '" + currentStep + "' failed: " + lastResponse.failureReason(),
                        lastResponse, executed, elapsed(start));
            }

            String output = lastResponse.content();
            history = new ArrayList<>(history);
            history.add(new StepResult(currentStep, output, Duration.between(stepStart, Instant.now())));

            // Determine next step via router, or advance sequentially
            Function<PipelineExecution, String> router = step.router();
            if (router != null) {
                PipelineExecution executionAfter = new PipelineExecution(task, history, stepCount);
                String nextStep = router.apply(executionAfter);
                log.debug("Pipeline router [{}] → [{}]", currentStep, nextStep);
                currentStep = (nextStep == null || nextStep.isBlank()) ? null : nextStep;
            } else {
                // Advance to the next declared step, or end if this was the last
                int idx = stepOrder.indexOf(currentStep);
                currentStep = (idx >= 0 && idx + 1 < stepOrder.size()) ? stepOrder.get(idx + 1) : null;
            }
        }

        return PipelineResult.success(
                lastResponse != null ? lastResponse.content() : "",
                lastResponse, executed, elapsed(start));
    }

    private static Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    public static Builder    builder()    { return new Builder(); }
    public static FsmBuilder fsmBuilder() { return new FsmBuilder(); }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private final List<String>              stepOrder = new ArrayList<>();
        private final Map<String, PipelineStep> steps     = new LinkedHashMap<>();
        private int maxSteps = DEFAULT_MAX_STEPS;

        private Builder() {}

        /**
         * Adds a named step to the pipeline. Steps execute in declaration order unless
         * a router redirects to a different step name. The step's task is built by
         * default as {@code execution.task().withInput(execution.lastOutput())} — use
         * {@link #step(String, AraAgent, Function)} to customise it.
         */
        public Builder step(String name, AraAgent agent) {
            return step(name, agent, null);
        }

        /**
         * Adds a named step whose task is built by {@code input} instead of the default
         * "previous step's raw output" — e.g. to combine two earlier named steps' results
         * with static text: {@code execution -> execution.task().withInput(
         * execution.resultOf("analyze").get().output() + " / " +
         * execution.resultOf("review").get().output())}.
         */
        public Builder step(String name, AraAgent agent, Function<PipelineExecution, AgentTask> input) {
            Objects.requireNonNull(name,  "step name must not be null");
            Objects.requireNonNull(agent, "agent must not be null");
            if (steps.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate step name: '" + name + "'");
            }
            stepOrder.add(name);
            steps.put(name, new PipelineStep(name, agent, input, null));
            return this;
        }

        /**
         * Adds a step that fans the incoming task out to every agent in {@code members},
         * running them concurrently on {@code executor}, and merges their responses per
         * {@code merge}/{@code failurePolicy} — see {@link ParallelAgent}. Equivalent to
         * {@code step(name, new ParallelAgent(AgentId.of(name), members, executor, merge,
         * failurePolicy))}, kept as a convenience so the common case does not require
         * importing {@code ParallelAgent} directly.
         *
         * <p>Like any other step, its input defaults to the previous step's raw output; use
         * the {@code Function<PipelineExecution, AgentTask>} overload of {@link #step} for a
         * custom shaper (e.g. to fan out over {@link PipelineExecution#initialInput()} instead).
         */
        public Builder parallel(String name, List<AraAgent> members, Executor executor,
                                AgentChain.MergeStrategy merge, AgentChain.FailurePolicy failurePolicy) {
            return step(name, new ParallelAgent(AgentId.of(name), members, executor, merge, failurePolicy));
        }

        /** Convenience overload using {@link AgentChain.FailurePolicy#FAIL_FAST}. */
        public Builder parallel(String name, List<AraAgent> members, Executor executor,
                                AgentChain.MergeStrategy merge) {
            return parallel(name, members, executor, merge, AgentChain.FailurePolicy.FAIL_FAST);
        }

        /**
         * Attaches a conditional router to {@code stepName}.
         *
         * <p>The router receives a {@link PipelineExecution} — a typed snapshot of the full
         * execution history up to and including this step — and returns the name of the next
         * step to execute. Return {@code null} or blank to end the pipeline immediately.
         *
         * @throws IllegalArgumentException if {@code stepName} was never declared via {@link #step}
         */
        public Builder route(String stepName, Function<PipelineExecution, String> router) {
            Objects.requireNonNull(stepName, "stepName must not be null");
            Objects.requireNonNull(router,   "router must not be null");
            PipelineStep existing = steps.get(stepName);
            if (existing == null) {
                throw new IllegalArgumentException(
                        "route(): unknown step '" + stepName + "' — declare it with step(...) first");
            }
            steps.put(stepName, existing.withRouter(router));
            return this;
        }

        /** Sets the hard cap on total step executions (including retries). Default: 20. */
        public Builder maxSteps(int max) {
            if (max <= 0) throw new IllegalArgumentException("maxSteps must be > 0");
            this.maxSteps = max;
            return this;
        }

        public AgentPipeline build() {
            if (stepOrder.isEmpty()) {
                throw new IllegalStateException("AgentPipeline must have at least one step");
            }
            return new AgentPipeline(this);
        }
    }

    // ── FsmBuilder ────────────────────────────────────────────────────────────

    /**
     * Declarative FSM-style builder for {@link AgentPipeline}.
     *
     * <p>Maps cleanly to FSM concepts:
     * <ul>
     *   <li><b>state</b>   — a named node backed by an {@link AraAgent}</li>
     *   <li><b>initial</b> — the entry state (defaults to first declared state)</li>
     *   <li><b>terminal</b> — accepting states; the pipeline ends successfully after them</li>
     *   <li><b>transition</b> — guard function that receives {@link PipelineExecution}
     *       and returns the next state name ({@code null} / blank to stop)</li>
     * </ul>
     *
     * <p>Usage:
     * <pre>{@code
     * AgentPipeline pipeline = AgentPipeline.fsmBuilder()
     *     .state("draft",    draftAgent)
     *     .state("review",   reviewAgent)
     *     .state("revise",   reviseAgent)
     *     .state("done",     doneAgent)
     *     .initial("draft")
     *     .terminal("done")
     *     .transition("draft",  "review")     // unconditional
     *     .transition("review", exec ->
     *         exec.lastOutput().contains("APPROVED") ? "done" : "revise")
     *     .transition("revise", "review")     // loop back
     *     .build();
     * }</pre>
     */
    public static final class FsmBuilder {

        private final Map<String, AraAgent>                            states      = new LinkedHashMap<>();
        private final Set<String>                                      terminals   = new LinkedHashSet<>();
        private final Map<String, Function<PipelineExecution, String>> transitions = new LinkedHashMap<>();
        private String initialState;
        private int    maxSteps = DEFAULT_MAX_STEPS;

        private FsmBuilder() {}

        /** Declares a state backed by the given agent. */
        public FsmBuilder state(String name, AraAgent agent) {
            Objects.requireNonNull(name,  "name must not be null");
            Objects.requireNonNull(agent, "agent must not be null");
            if (states.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate state: '" + name + "'");
            }
            states.put(name, agent);
            return this;
        }

        /** Sets the entry state. Defaults to the first declared state if not called. */
        public FsmBuilder initial(String stateName) {
            this.initialState = Objects.requireNonNull(stateName, "stateName must not be null");
            return this;
        }

        /** Marks states as terminal (accepting). The pipeline ends successfully after any of them. */
        public FsmBuilder terminal(String... stateNames) {
            for (String s : stateNames) {
                terminals.add(Objects.requireNonNull(s, "terminal state name must not be null"));
            }
            return this;
        }

        /** Conditional transition: the router receives {@link PipelineExecution} and returns the next state. */
        public FsmBuilder transition(String from, Function<PipelineExecution, String> router) {
            Objects.requireNonNull(from,   "from must not be null");
            Objects.requireNonNull(router, "router must not be null");
            transitions.put(from, router);
            return this;
        }

        /** Unconditional transition: always moves from {@code from} to {@code to}. */
        public FsmBuilder transition(String from, String to) {
            Objects.requireNonNull(to, "to must not be null");
            return transition(from, __ -> to);
        }

        /** Sets the hard cap on total state executions (including loops). Default: 20. */
        public FsmBuilder maxSteps(int max) {
            if (max <= 0) throw new IllegalArgumentException("maxSteps must be > 0");
            this.maxSteps = max;
            return this;
        }

        public AgentPipeline build() {
            if (states.isEmpty()) {
                throw new IllegalStateException("FSM must have at least one state");
            }
            String entry = (initialState != null) ? initialState : states.keySet().iterator().next();
            if (!states.containsKey(entry)) {
                throw new IllegalStateException("Initial state '" + entry + "' not declared");
            }

            Builder b = AgentPipeline.builder().maxSteps(maxSteps);

            // initialState first so the pipeline enters there, then remaining states
            b.step(entry, states.get(entry));
            states.entrySet().stream()
                    .filter(e -> !e.getKey().equals(entry))
                    .forEach(e -> b.step(e.getKey(), e.getValue()));

            // Routers: terminal → stop, explicit transitions, else sequential
            for (String state : states.keySet()) {
                if (terminals.contains(state)) {
                    b.route(state, __ -> null);
                } else if (transitions.containsKey(state)) {
                    b.route(state, transitions.get(state));
                }
            }

            return b.build();
        }
    }
}
