package io.ara.runtime.workflow;

import io.ara.core.agent.AgentResponse;

import java.util.List;
import java.util.Objects;

/**
 * How one node occurrence finished. ADR-052 names all three as part of D1's journal
 * entry shape; this increment is what gives them somewhere to come from:
 *
 * <ul>
 *   <li>{@link Completed} — the node's {@link WorkflowNode#body()} returned normally.</li>
 *   <li>{@link Failed} — the body threw. Any exception other than
 *       {@link WorkflowNodeSuspendedException} counts, with the exception's message as
 *       the reason. There is no dedicated "failed" signal a body throws deliberately:
 *       an ordinary exception already means "this went wrong", which is exactly what
 *       Java gives every caller for free.</li>
 *   <li>{@link Suspended} — the body threw {@link WorkflowNodeSuspendedException}. D1
 *       treats this like {@link Failed} for now: it stops the run rather than losing
 *       track of state. Making a suspension genuinely resumable — waiting for an
 *       approval decision instead of stopping — is ADR-052 D6's job, layered on top of
 *       ADR-048's checkpoint store; nothing here pretends to do that yet.</li>
 * </ul>
 */
public sealed interface NodeOutcome {

    /**
     * A node that returned normally. {@code response} is non-{@code null} only for an
     * agent-shaped node (ADR-052 D2) — it is the {@link AgentResponse} the node's agent
     * produced, carried so the scheduler can charge the run budget from its token/cost
     * totals and {@code WorkflowStrategy} can report the prompt/output split. An opaque
     * {@link WorkflowNode#body()} node has no response, only {@code content}.
     */
    record Completed(String content, List<String> selectedTargets, AgentResponse response) implements NodeOutcome {
        public Completed {
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(selectedTargets, "selectedTargets must not be null");
            selectedTargets = List.copyOf(selectedTargets);
        }

        /** Backwards-compatible constructor: an opaque node, with no agent response. */
        public Completed(String content, List<String> selectedTargets) {
            this(content, selectedTargets, null);
        }
    }

    record Failed(String reason) implements NodeOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    record Suspended(String reason) implements NodeOutcome {
        public Suspended {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
