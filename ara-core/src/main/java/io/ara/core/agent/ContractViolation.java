package io.ara.core.agent;

import io.ara.core.agent.processor.ProcessingResult;

import java.util.List;
import java.util.Objects;

/**
 * Why an {@link AgentContract} stopped a task: the phase that refused it and, when the
 * processor could say, the individual problems.
 *
 * <p>Carried by {@link AgentResponse#violation()}, next to the prose in
 * {@link AgentResponse#failureReason()}. The two are not redundant. The reason is for a
 * person reading a log; this is for a caller that has to act on the failure — an HTTP
 * layer choosing between "your request is malformed" and "the agent got it wrong", a client
 * that wants to show which field to fix.
 *
 * <p>Who is at fault follows from the phase: {@link Phase#INPUT} and {@link Phase#MEDIA} are
 * the caller's — the task never reached the model; {@link Phase#OUTPUT} is the agent's.
 *
 * @param phase  which part of the contract refused the task
 * @param issues the individual problems, in {@code ProcessingResult.Issue} form; empty when
 *               the rejecting processor offered only a reason
 */
public record ContractViolation(Phase phase, List<ProcessingResult.Issue> issues) {

    /** The part of the contract that refused the task. */
    public enum Phase {
        /** The input chain rejected the task's input. */
        INPUT,
        /** A media validator rejected the task's attachments. */
        MEDIA,
        /** The output chain rejected the agent's answer (after any repair attempts). */
        OUTPUT;

        /** {@code true} when the caller can fix the failure by sending a different task. */
        public boolean callerFault() {
            return this != OUTPUT;
        }
    }

    public ContractViolation {
        Objects.requireNonNull(phase, "phase must not be null");
        issues = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
    }
}
