package io.ara.runtime.workflow;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;

import java.util.Objects;
import java.util.function.Function;

/**
 * An {@link AraAgent} wired as a {@link WorkflowNode} (ADR-052 D2's "agent-shaped node"):
 * the node executes the agent and its {@link io.ara.core.agent.AgentResponse} becomes the
 * node's outcome, so token usage and cost flow into the journal instead of being lost in a
 * plain {@code Function<String,String>} body.
 *
 * <p>D1 kept {@link WorkflowNode#body()} deliberately opaque — a plain function, not an
 * agent — because the scheduler's activation rule was all it had to prove. This is the
 * increment that gives a node a real agent to run, without teaching the scheduler any node
 * kind: {@link WorkflowNode#run(String)} is the single execution path, and it branches on
 * this binding (the node knows its own shape) rather than the scheduler branching on it
 * (ADR-054 D7's FF-6: no {@code case}/{@code instanceof} on node type inside the scheduler).
 *
 * @param agent      the agent to execute for this node; never {@code null}
 * @param taskShaper how the node's composed input becomes the agent's {@link AgentTask} —
 *                   e.g. to carry {@code RunContext}, attachments or a session id. {@code
 *                   null} means the plain {@code AgentTask.of(input)}, the same default a
 *                   caller who has no per-run context to thread through wants.
 */
public record AgentBinding(AraAgent agent, Function<String, AgentTask> taskShaper) {

    public AgentBinding {
        Objects.requireNonNull(agent, "agent must not be null");
    }

    /** The task this node hands the agent for {@code input}. */
    public AgentTask taskFor(String input) {
        return taskShaper != null ? taskShaper.apply(input) : AgentTask.of(input);
    }
}
