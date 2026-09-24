package io.ara.runtime.workflow;

import io.ara.core.agent.AgentResponse;

import java.util.Objects;

/**
 * What one node occurrence produced: its content, plus the {@link AgentResponse} when the
 * node is agent-shaped ({@code null} for an opaque {@link WorkflowNode#body()} node).
 *
 * <p>The response is carried rather than just a {@link io.ara.core.budget.Spend} because
 * the two consumers want different projections of it: the scheduler charges the run budget
 * from its token/cost totals, while {@code WorkflowStrategy} reports the prompt/output
 * token split an {@link io.ara.core.agent.ExecutionResult} exposes. Collapsing it to one
 * number here would force one of the two to guess.
 *
 * <p>Package-private: it is an internal hand-off between {@link WorkflowNode#run} and
 * {@link DataflowScheduler}, never part of the public graph model.
 */
record NodeOutput(String content, AgentResponse response) {

    NodeOutput {
        Objects.requireNonNull(content, "content must not be null");
    }

    /** An opaque node's output — content only, no agent response. */
    static NodeOutput of(String content) {
        return new NodeOutput(content, null);
    }
}
