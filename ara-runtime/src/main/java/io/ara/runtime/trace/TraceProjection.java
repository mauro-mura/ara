package io.ara.runtime.trace;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.DelegatedBy;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.StepType;
import io.ara.core.common.Money;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.runtime.agent.FailureKind;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Projects one completed single-agent execution into a run trace — the first of ADR-0068
 * D1's two emission points ("each accumulated {@code ExecutionStep} produces a
 * {@code TraceSpan}"), the deferred follow-up of that ADR. Pure and side-effect-free apart
 * from the content it puts into the {@link BlobStore}; the caller appends the spans to a
 * {@code TraceStore}.
 *
 * <p><b>Shape.</b> One <em>root</em> span for the whole execution ({@code spanId =
 * "<agentId>/<taskId>#run"}) carrying the token/cost totals, the content-addressed
 * prompt/output refs and the outcome — plus one <em>child</em> span per
 * {@link ExecutionStep} ({@code spanId = "<agentId>/<taskId>#<iteration>-<ordinal>"}, parent =
 * the root) for the reasoning structure. {@code ExecutionStep} carries no per-step tokens or
 * cost, so only the root span is billed; the children are structural.
 *
 * <p><b>Delegation lands in the same run.</b> A delegated task shares its caller's run id (the
 * delegation tool hands it the caller's workflow id), so one run holds the whole tree. The
 * root of a delegated execution is therefore <em>not</em> parentless: its parent is the root of
 * the run that delegated ({@code RunContext.DELEGATED_BY_KEY}). Exactly one span per run has no
 * parent, which is what readers that look for "the root" rely on.
 *
 * <p><b>Why the task id is in every span id.</b> Span ids must be unique within a run. With
 * {@code "<agentId>#run"}, as this used to be, the same agent working twice in one run (two
 * delegations to one worker, or two tasks in one session, which share the session's run id)
 * wrote two spans with the same id, and whoever read the run back lost one. The agent id is
 * kept in front so a span still reads as "whose" at a glance.
 *
 * <p>The root span's {@link TraceSpan#failureKind()} is set from
 * {@link FailureKind#classify} on a failure — this also wires the ADR-0074 D6 field that
 * the dashboard's failure-mode panel reads.
 *
 * <p><b>Not here yet</b>: the second emission point — a {@code TraceSpan} per workflow
 * journal entry (ADR-052 D1). {@code specHash} is always {@code null} (agents are not
 * {@code AgentSpec}-tracked yet) and {@code contextProvenanceUntrusted} is always
 * {@code false} (ADR-0068 D3's "recorded at emission" signal is not plumbed through).
 */
public final class TraceProjection {

    private TraceProjection() {}

    /** The run id for a single-agent execution: correlation id, else session id, else task id. */
    public static String runIdOf(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (task.correlationId() != null && !task.correlationId().isBlank()) {
            return task.correlationId();
        }
        if (task.sessionId() != null && task.sessionId().value() != null && !task.sessionId().value().isBlank()) {
            return task.sessionId().value();
        }
        return task.taskId();
    }

    /** The spans for one completed execution — root first, then one per {@link ExecutionStep}. */
    public static List<TraceSpan> project(AgentTask task, AgentResponse response, BlobStore blobs) {
        return project(task, response, blobs, null);
    }

    /**
     * As above, stamping every span with {@code specHash} — which spec produced this run.
     *
     * <p>The hash is the key that joins a run to everything that is measured per spec: the archive's
     * lineage, the eval results of that variant, the suite-vs-production gap. Without it a span says
     * <em>which agent</em> ran but not <em>which behaviour</em>, and every panel that groups by spec
     * has nothing to group on. It is passed in, not computed: what makes two configs "the same spec"
     * is the meta-agent's definition, not this module's, so the caller that owns it supplies it
     * ({@code null} for an agent that is not tracked to a spec).
     */
    public static List<TraceSpan> project(AgentTask task, AgentResponse response, BlobStore blobs, String specHash) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(blobs, "blobs must not be null");

        String runId = runIdOf(task);
        String agentId = response.agentId().value();
        String rootSpanId = rootSpanIdOf(agentId, task.taskId());

        Instant endedAt = response.completedAt();
        Instant startedAt = endedAt.minus(response.elapsedTime());
        if (startedAt.isAfter(endedAt)) {
            startedAt = endedAt;
        }

        String promptRef = ref(blobs, task.input());
        boolean ok = response.isSuccess();
        String outputRef = ok ? ref(blobs, response.content()) : null;
        SpanStatus status = ok
                ? new SpanStatus.Completed()
                : new SpanStatus.Failed(Objects.requireNonNullElse(response.failureReason(), "agent execution failed"));

        TraceSpan.Builder root = TraceSpan.builder(runId, rootSpanId, agentId)
                .parentSpanId(delegatingRootOf(task))
                .promptRef(promptRef)
                .outputRef(outputRef)
                .tokensIn(Math.max(0, response.inputTokens()))
                .tokensOut(Math.max(0, response.outputTokens()))
                .cost(response.estimatedCost() != null ? response.estimatedCost() : Money.ZERO_EUR)
                .status(status)
                .startedAt(startedAt)
                .endedAt(endedAt);
        root.specHash(specHash);
        if (!ok) {
            root.failureKind(FailureKind.of(response).name());   // ADR-0074 D6 — typed for a contract refusal, by reason text otherwise
        }

        List<TraceSpan> spans = new ArrayList<>();
        spans.add(root.build());

        List<ExecutionStep> steps = response.steps();
        // An ExecutionStep carries no clock, so the run's interval is divided evenly between its
        // steps: an ORDER, not a measurement. Giving every step the whole run's interval (as this did)
        // made "the steps before this one" empty for every step, which silently disabled the root-cause
        // search that reads earlier steps of the same run. The slice is at least one nanosecond so
        // even an instantaneous run keeps a strict order.
        Duration slice = steps.isEmpty() ? Duration.ZERO : response.elapsedTime().dividedBy(steps.size());
        if (slice.isZero() || slice.isNegative()) {
            slice = Duration.ofNanos(1);
        }
        for (int i = 0; i < steps.size(); i++) {
            ExecutionStep step = steps.get(i);
            Instant stepStart = startedAt.plus(slice.multipliedBy(i));
            Instant stepEnd = i == steps.size() - 1 && !slice.equals(Duration.ofNanos(1))
                    ? endedAt : stepStart.plus(slice);
            String stepPrompt = step.type() == StepType.TOOL_CALL
                    ? ref(blobs, step.toolId() + " " + Objects.requireNonNullElse(step.arguments(), ""))
                    : null;
            spans.add(TraceSpan.builder(runId, agentId + "/" + task.taskId() + "#" + step.iteration() + "-" + i, agentId)
                    .parentSpanId(rootSpanId)
                    .promptRef(stepPrompt)
                    .outputRef(ref(blobs, step.content()))
                    .status(new SpanStatus.Completed())
                    .specHash(specHash)
                    .startedAt(stepStart)
                    .endedAt(stepEnd)
                    .build());
        }
        return spans;
    }

    /** A minimal Failed root span for an execution that threw before producing an {@link AgentResponse}. */
    public static TraceSpan failedByException(AgentTask task, String agentId, Throwable error, BlobStore blobs) {
        Instant now = Instant.now();
        return TraceSpan.builder(runIdOf(task), rootSpanIdOf(agentId, task.taskId()), agentId)
                .parentSpanId(delegatingRootOf(task))
                .promptRef(ref(blobs, task.input()))
                .status(new SpanStatus.Failed("Unexpected error: "
                        + Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName())))
                .failureKind(FailureKind.classify("Unexpected error:").name())
                .startedAt(now)
                .endedAt(now)
                .build();
    }

    /** The id of the root span of {@code agentId}'s execution of {@code taskId}. */
    public static String rootSpanIdOf(String agentId, String taskId) {
        return agentId + "/" + taskId + "#run";
    }

    /** The root span of the run that delegated {@code task}, or {@code null} if nobody did. */
    private static String delegatingRootOf(AgentTask task) {
        DelegatedBy by = task.runContext().opaque(RunContext.DELEGATED_BY_KEY, DelegatedBy.class);
        return by == null ? null : rootSpanIdOf(by.agentId(), by.taskId());
    }

    private static String ref(BlobStore blobs, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return blobs.put(content.getBytes(StandardCharsets.UTF_8));
    }
}
