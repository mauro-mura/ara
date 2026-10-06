package io.ara.core.agent;

import io.ara.core.common.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * Something that happened during one run of an agent, delivered to the listener a caller set
 * with {@link AgentTask#withEventListener}. A user interface subscribes to it to show an agent
 * working: it thinks, calls a tool, gets the result, answers.
 *
 * <p>There are three events, and a run produces exactly one {@link RunStarted}, any number of
 * {@link StepRecorded}, and exactly one {@link RunFinished}, even when the run is refused or
 * fails: a page waiting for the end of a run must never be left waiting.
 *
 * <p><b>Steps are the vocabulary.</b> What happened inside a run is an {@link ExecutionStep}
 * (thought, tool call, observation, final answer, speak, reflection): the same value that ends
 * up in {@code AgentResponse.steps()}. A tool call and its result are two consecutive steps; a
 * delegation is a tool call, followed by the events of the agent that was delegated to. No
 * second vocabulary exists to keep in step with it.
 *
 * <p><b>Envelope.</b> Every event says which run it belongs to ({@code taskId}, {@code
 * agentId}), which run delegated it ({@code parentTaskId}, {@code null} for a run nobody
 * delegated), the workflow it is part of ({@code correlationId}, shared by a whole delegation
 * chain), its position in its own run ({@code seq}, from 0, per {@code taskId}) and when it
 * happened. Order between runs is not defined: use {@code at}.
 *
 * <p>Immutable; thread-safe. See {@link AgentTask#withEventListener} for the delivery contract.
 */
public sealed interface AgentEvent
        permits AgentEvent.RunStarted, AgentEvent.StepRecorded, AgentEvent.RunFinished {

    String taskId();

    String agentId();

    /** The task that delegated this run, or {@code null} for a run nobody delegated. */
    String parentTaskId();

    String correlationId();

    /** Position within this {@code taskId}'s events, starting at 0 and without gaps. */
    long seq();

    Instant at();

    /** The run began. Carries nothing beyond the envelope: the input is in the task the caller holds. */
    record RunStarted(String taskId, String agentId, String parentTaskId, String correlationId,
                      long seq, Instant at) implements AgentEvent {

        public RunStarted {
            checkEnvelope(taskId, agentId, seq, at);
        }
    }

    /**
     * The run recorded a step. {@code step} is exactly what will be in the response's steps, with
     * one exception worth knowing: a strategy that retries (reflexion) returns only its last
     * attempt's steps, while events show every attempt. {@link ExecutionStep#iteration()}
     * restarts at each attempt, which is how they can be told apart.
     */
    record StepRecorded(String taskId, String agentId, String parentTaskId, String correlationId,
                        long seq, Instant at, ExecutionStep step) implements AgentEvent {

        public StepRecorded {
            checkEnvelope(taskId, agentId, seq, at);
            Objects.requireNonNull(step, "step must not be null");
        }
    }

    /**
     * The run ended, successfully or not. A refusal (agent terminated, session busy), a budget or
     * timeout stop, a cancellation and an exception all arrive here with {@code success == false}
     * and the reason in {@code failureReason}.
     *
     * <p>It describes the run as the agent instance saw it. A wrapper that changes the response
     * afterwards (a contract that rejects the answer) is not reflected: that outcome is in
     * {@code AgentResponse.violation()}.
     */
    record RunFinished(String taskId, String agentId, String parentTaskId, String correlationId,
                       long seq, Instant at, boolean success, AgentState finalState,
                       int iterationsUsed, int inputTokens, int outputTokens, Money estimatedCost,
                       String failureReason) implements AgentEvent {

        public RunFinished {
            checkEnvelope(taskId, agentId, seq, at);
            Objects.requireNonNull(finalState, "finalState must not be null");
            Objects.requireNonNull(estimatedCost, "estimatedCost must not be null");
        }
    }

    /** The checks every event shares; private, so the envelope's rules are not part of the API. */
    private static void checkEnvelope(String taskId, String agentId, long seq, Instant at) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (seq < 0) throw new IllegalArgumentException("seq must be >= 0, got: " + seq);
    }
}
