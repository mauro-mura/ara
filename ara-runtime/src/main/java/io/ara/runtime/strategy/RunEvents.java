package io.ara.runtime.strategy;

import io.ara.core.agent.AgentEvent;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.DelegatedBy;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.RunContext;
import io.ara.core.common.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The emitter of one run's {@link AgentEvent}s: it owns the run's sequence counter and the
 * listener, and every piece of code that records a step goes through it.
 *
 * <p><b>Design.</b> {@code AgentInstance.execute} creates one emitter per run and wraps the whole
 * body, so a run gets exactly one {@code RunStarted} and one {@code RunFinished} whatever
 * happens inside (a refusal, an exception, any strategy, a pipeline or a workflow) without the
 * strategies knowing. Steps are different: they are born inside the strategies, in about
 * eighteen places that each used to do {@code steps.add(ExecutionStep.x(...))}. Those places now
 * call {@link #record}, which adds to the list and emits, so the list and the events cannot
 * disagree about what a strategy recorded, and the duplication of that line shrinks to one.
 * Alternatives rejected: deriving events from the interceptors (global to the runtime, not per
 * run, and they see LLM and tool calls rather than the agent's steps) and a second event
 * vocabulary next to {@link ExecutionStep} (two lists of step kinds to keep aligned by hand).
 *
 * <p><b>Two opaque values, opposite rules.</b> The user's listener is in the task's
 * {@link RunContext} and is <em>inherited</em> by every delegated run, so one listener sees the
 * whole tree. This emitter is in the same context and is <em>replaced</em> by every run: if a
 * delegate kept its caller's, it would emit under the caller's task id and sequence. That is the
 * reverse of how {@code AgentInstance} seeds state and scopes ("attach only if absent"), on
 * purpose. The delegate learns who its parent is from {@link DelegatedBy}, not from the emitter
 * it overwrites.
 *
 * <p><b>Threads.</b> Steps of one run are recorded from the thread that drives the run (even
 * with parallel tools: results are collected and recorded after all of them finish), so one
 * {@code taskId}'s events are delivered in order from one thread; a delegated run emits from its
 * own thread, so the listener can be called concurrently and must be thread-safe. The sequence
 * counter is atomic and delivery happens outside any lock: calling arbitrary code inside a lock
 * is how a slow listener would stall unrelated work.
 *
 * <p>A {@link RuntimeException} from the listener never stops the run: it is logged once per
 * run (not once per event, which would flood the log) and delivery continues. An {@link Error}
 * propagates.
 *
 * <p>Thread-safe.
 */
public final class RunEvents {

    /** The {@link RunContext} opaque key under which a run's own emitter is found. */
    public static final String KEY = "io.ara.run.events";

    private static final Logger log = LoggerFactory.getLogger(RunEvents.class);

    private final Consumer<AgentEvent> listener;
    private final String taskId;
    private final String agentId;
    private final String parentTaskId;
    private final String correlationId;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean listenerFailureLogged = new AtomicBoolean();

    private RunEvents(Consumer<AgentEvent> listener, String taskId, String agentId, String parentTaskId,
                      String correlationId) {
        this.listener = listener;
        this.taskId = taskId;
        this.agentId = agentId;
        this.parentTaskId = parentTaskId;
        this.correlationId = correlationId;
    }

    /**
     * The emitter for {@code agentId}'s run of {@code task}, or {@code null} when nobody listens
     * (so the common case costs nothing).
     */
    public static RunEvents forRun(AgentTask task, String agentId) {
        Consumer<AgentEvent> listener = task.eventListener();
        if (listener == null) {
            return null;
        }
        DelegatedBy delegatedBy = task.runContext().opaque(RunContext.DELEGATED_BY_KEY, DelegatedBy.class);
        return new RunEvents(listener, task.taskId(), agentId,
                delegatedBy == null ? null : delegatedBy.taskId(), task.correlationId());
    }

    /** A copy of {@code task} that carries this emitter, replacing any it inherited. */
    public AgentTask attachTo(AgentTask task) {
        return task.withAttachment(KEY, this);
    }

    /**
     * Adds {@code step} to {@code steps} and, if the run has an emitter, announces it. The one
     * place a strategy records a step.
     */
    public static void record(AgentTask task, List<ExecutionStep> steps, ExecutionStep step) {
        steps.add(step);
        RunEvents events = task.runContext().opaque(KEY, RunEvents.class);
        if (events != null) {
            events.step(step);
        }
    }

    public void started() {
        emit(seq -> new AgentEvent.RunStarted(taskId, agentId, parentTaskId, correlationId, seq, Instant.now()));
    }

    void step(ExecutionStep step) {
        emit(seq -> new AgentEvent.StepRecorded(taskId, agentId, parentTaskId, correlationId, seq,
                Instant.now(), step));
    }

    /** The run ended with {@code response}. */
    public void finished(AgentResponse response) {
        emit(seq -> new AgentEvent.RunFinished(taskId, agentId, parentTaskId, correlationId, seq,
                Instant.now(), response.isSuccess(), response.finalState(), response.iterationsUsed(),
                response.inputTokens(), response.outputTokens(), response.estimatedCost(),
                response.failureReason()));
    }

    /** The run ended by throwing {@code error}; the exception itself still propagates to the caller. */
    public void failed(Throwable error) {
        String reason = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        emit(seq -> new AgentEvent.RunFinished(taskId, agentId, parentTaskId, correlationId, seq,
                Instant.now(), false, AgentState.FAILED, 0, 0, 0, Money.ZERO_EUR, reason));
    }

    private void emit(LongFunction<AgentEvent> event) {
        AgentEvent built = event.apply(sequence.getAndIncrement());
        try {
            listener.accept(built);
        } catch (RuntimeException e) {
            if (listenerFailureLogged.compareAndSet(false, true)) {
                log.warn("Event listener threw on {} (task [{}], agent [{}], seq {}); the run goes on and "
                        + "later failures of this listener are not logged", built.getClass().getSimpleName(),
                        taskId, agentId, built.seq(), e);
            }
        }
    }
}
