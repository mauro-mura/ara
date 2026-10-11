package io.ara.runtime.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The pause between phase 1 and phase 2 of {@link PlanExecuteStrategy} when
 * {@code StrategyConfig.PlanExecute.approvePlan} is on: the plan goes to the runtime's
 * {@link ApprovalGate} as an {@link ApprovalRequest}, and no step runs until a person has decided.
 *
 * <p>The three decisions mean what they mean for a tool call, applied to the plan:
 * <ul>
 *   <li>{@link ApprovalDecision.Approved} — the plan runs as it was made;</li>
 *   <li>{@link ApprovalDecision.Modified} — the plan runs as the person corrected it. The new
 *       payload is either the list of step goals or a text {@link PlanReader} can read (the JSON
 *       plan, or a numbered list), so a console can send back whatever its user typed;</li>
 *   <li>{@link ApprovalDecision.Rejected} — nothing runs and the task ends as failed, with the
 *       reason the person gave. There is no replan after a rejection: replanning is for a step
 *       that went wrong, and asking the model again would make the person approve again with
 *       nothing said about what they wanted different.</li>
 * </ul>
 *
 * <p>Only the first plan is submitted. A replan rewrites the part of the plan still ahead of a
 * step that failed or asked for it, and a person who approved the plan has not been asked about
 * that rewrite. Gating it as well would stop a run that is already under way for a decision whose
 * subject (the failure) the person has already seen in the trace.
 *
 * <p>The decision is part of the trace: a {@code approve_plan} tool call that carries the plan,
 * followed by one observation that says how it was decided. A listener therefore sees the
 * approval where it happened, in order with the steps around it, without a new event type.
 *
 * <p>The wait counts against the task's deadline, as a tool call that waits for a person does
 * (see {@code ApprovalToolRegistry}): a run that has to be approved needs an
 * {@code executionTimeout} that leaves room for the person.
 *
 * <p>Stateless: every method is static and takes everything it needs, so the strategy's singleton
 * can be shared by concurrent tasks. Thread-safe.
 */
final class PlanApproval {

    /** The action name of the request and the id of the trace entry; a console shows it as the title. */
    static final String ACTION = "approve_plan";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanApproval() {
    }

    /** How the review ended: the steps to execute, or the failed result to hand back as it is. */
    sealed interface Review {
        record Approved(List<String> steps) implements Review { }
        record Stopped(ExecutionResult result) implements Review { }
    }

    /**
     * Submits {@code steps} to {@code gate} and waits for the decision.
     *
     * @param timeout how long a person has; past it the task ends as failed rather than hanging
     */
    static Review review(PlanRun run, PlanTally tally, List<String> steps, ApprovalGate gate, Duration timeout) {
        tally.record(ExecutionStep.toolCall(ACTION, planJson(steps), tally.iterations));

        ApprovalRequest request = ApprovalRequest.of(
                run.config().agentId().value(), ACTION, List.copyOf(steps), timeout,
                Map.of("taskId", run.task().taskId(), "task", run.task().input()));
        ApprovalDecision decision;
        try {
            decision = gate.requestApproval(request).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Review.Stopped(tally.fail("Cancelled"));
        } catch (ExecutionException | CompletionException failed) {
            return new Review.Stopped(tally.fail(failedWait(failed.getCause() != null ? failed.getCause() : failed)));
        }
        return switch (decision) {
            case ApprovalDecision.Approved approved -> {
                tally.record(ExecutionStep.observation("Plan approved.", tally.iterations));
                yield new Review.Approved(steps);
            }
            case ApprovalDecision.Modified modified -> corrected(modified.newPayload(), tally);
            case ApprovalDecision.Rejected rejected -> {
                tally.record(ExecutionStep.observation("Plan rejected: " + rejected.reason(), tally.iterations));
                yield new Review.Stopped(tally.fail("The plan was rejected: " + rejected.reason()));
            }
        };
    }

    /** The plan the person sent back, or the failure when it holds no readable step. */
    private static Review corrected(Object payload, PlanTally tally) {
        List<String> steps = switch (payload) {
            case List<?> list -> list.stream().map(String::valueOf).map(String::strip)
                    .filter(goal -> !goal.isBlank()).toList();
            case CharSequence text -> PlanReader.read(text.toString());
            default -> List.of();
        };
        if (steps.isEmpty()) {
            tally.record(ExecutionStep.observation(
                    "The corrected plan has no readable step.", tally.iterations));
            return new Review.Stopped(tally.fail("The approver sent back a plan with no readable step: "
                    + payload));
        }
        tally.record(ExecutionStep.observation(
                "Plan approved with changes: " + planJson(steps), tally.iterations));
        return new Review.Approved(steps);
    }

    private static String failedWait(Throwable cause) {
        return cause instanceof ApprovalTimeoutException
                ? "The plan was not approved in time: " + cause.getMessage()
                : "The plan could not be submitted for approval: " + cause;
    }

    /**
     * The step goals as {@code {"steps":["...","..."]}}. Only the goals: that is all the strategy
     * keeps of a plan (see {@link PlanReader}), and inventing an {@code id} and an empty
     * {@code dependsOn} for each would show the approver dependencies that nobody wrote.
     * {@link PlanReader} reads this shape, so a corrected plan can come back in it unchanged.
     */
    private static String planJson(List<String> steps) {
        try {
            return MAPPER.writeValueAsString(Map.of("steps", steps));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a plan of strings is always serialisable", e);
        }
    }
}
