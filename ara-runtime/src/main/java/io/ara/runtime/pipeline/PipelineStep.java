package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;

import java.util.Objects;
import java.util.function.Function;

/**
 * One named step of an {@link AgentPipeline}: the agent that runs it, how its input
 * task is built, and how the pipeline routes after it. Replaces what used to be three
 * independently-mutated parallel structures ({@code stepOrder}/{@code steps}/{@code
 * routers}) keyed by the same step name — a single source of truth per step instead.
 *
 * @param name   the step's declared name
 * @param agent  the agent that executes this step
 * @param input  builds this step's task from the execution so far; {@code null} means
 *               the default: {@code execution.task().withInput(execution.lastOutput())}
 * @param router decides the next step name from the execution so far (including this
 *               step's own result); {@code null} means "advance to the next declared step"
 */
record PipelineStep(
        String                                  name,
        AraAgent                                agent,
        Function<PipelineExecution, AgentTask>  input,
        Function<PipelineExecution, String>     router
) {
    PipelineStep {
        Objects.requireNonNull(name,  "name must not be null");
        Objects.requireNonNull(agent, "agent must not be null");
    }

    /** Returns a copy of this step with its router replaced. */
    PipelineStep withRouter(Function<PipelineExecution, String> newRouter) {
        return new PipelineStep(name, agent, input, newRouter);
    }

    /** Builds this step's task, applying the custom {@link #input} shaper if one was declared. */
    AgentTask buildTask(PipelineExecution execution) {
        return input != null
                ? input.apply(execution)
                : execution.task().withInput(execution.lastOutput());
    }
}
