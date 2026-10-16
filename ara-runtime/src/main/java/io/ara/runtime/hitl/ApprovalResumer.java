package io.ara.runtime.hitl;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.PendingApproval;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Puts the questions of suspended runs in front of the operator, and continues each run when
 * they are all answered (ADR-048 D2).
 *
 * <p>A run that stops before a gated tool call is only a checkpoint in a store: nobody is
 * waiting on it. This is the part that waits instead. For every suspended run it registers the
 * run's approval requests with the {@link ApprovalGate} — which is what makes them appear in
 * the operator's list and lets {@code submit} answer them — and when every one of the turn's
 * requests has an answer it resumes the run on a virtual thread, by handing the agent a task
 * built from the checkpoint and the decisions. No thread waits in the meantime.
 *
 * <p><b>The agent is found when the decision arrives, not when the run suspends.</b> After a
 * restart the checkpoints are read back before the application has registered its agents;
 * looking the agent up late means the order does not matter. If it is still missing when the
 * decision comes, the failure is logged and the checkpoint stays in the store, so nothing is lost.
 *
 * <p><b>A request that expires counts as a refusal.</b> The run resumes and the model is told
 * the action was refused because approval timed out — the same outcome as today, when a thread
 * waits and the timeout fires. The expiry is an absolute time, so a request that ran out while
 * the process was down expires as soon as it is registered again.
 *
 * <p>Thread-safe.
 */
public final class ApprovalResumer {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumer.class);

    private final ApprovalGate gate;
    private final CheckpointStore store;
    private final Function<AgentId, Optional<AraAgent>> agents;
    private final Consumer<AgentResponse> resumedRuns;

    /** Request ids already registered, so recovering a run that is also live registers it once. */
    private final Set<String> watching = ConcurrentHashMap.newKeySet();

    /**
     * @param agents      looks an agent up by id, at the moment a run is to be continued
     * @param resumedRuns receives the response of each run this continues — whoever called
     *                    {@code execute} on the original run already has its suspension response
     */
    public ApprovalResumer(ApprovalGate gate, CheckpointStore store,
                           Function<AgentId, Optional<AraAgent>> agents, Consumer<AgentResponse> resumedRuns) {
        this.gate        = Objects.requireNonNull(gate, "gate must not be null");
        this.store       = Objects.requireNonNull(store, "store must not be null");
        this.agents      = Objects.requireNonNull(agents, "agents must not be null");
        this.resumedRuns = Objects.requireNonNull(resumedRuns, "resumedRuns must not be null");
    }

    /** Registers again the questions of every run the store holds as suspended — what a restart does. */
    public void recover() {
        List<RunCheckpoint> suspended = store.awaitingApproval();
        suspended.forEach(this::watch);
        if (!suspended.isEmpty()) {
            log.info("Recovered {} run(s) waiting for approval", suspended.size());
        }
    }

    /** Waits, without a thread, for the answers to {@code checkpoint}'s questions, then resumes the run. */
    public void watch(RunCheckpoint checkpoint) {
        PendingApproval pending = checkpoint.pendingApproval();
        if (pending == null) {
            throw new IllegalArgumentException("checkpoint of task " + checkpoint.taskId() + " waits for no approval");
        }
        List<ApprovalRequest> requests = pending.requests();
        // The turn's requests are always watched together, so the first one stands for all.
        if (!watching.add(requests.get(0).requestId())) {
            return;   // already watched: a live suspension that recover() also found
        }
        requests.stream().skip(1).forEach(request -> watching.add(request.requestId()));
        Map<String, CompletableFuture<ApprovalDecision>> answers = new HashMap<>();
        for (ApprovalRequest request : requests) {
            answers.put(request.requestId(), gate.requestApproval(request).handle(
                    (decision, failure) -> decision != null ? decision : refusalFor(failure)));
        }
        CompletableFuture.allOf(answers.values().toArray(CompletableFuture[]::new)).thenRun(() ->
                Thread.ofVirtual().name("ara-resume-" + checkpoint.taskId()).start(() -> {
                    Map<String, ApprovalDecision> decisions = new HashMap<>();
                    answers.forEach((id, answer) -> decisions.put(id, answer.join()));
                    resume(checkpoint, requests, decisions);
                }));
    }

    private static ApprovalDecision refusalFor(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof ApprovalTimeoutException) {
            return new ApprovalDecision.Rejected("approval timed out");
        }
        if (cause instanceof CancellationException) {
            return new ApprovalDecision.Rejected("approval cancelled");
        }
        return new ApprovalDecision.Rejected("approval failed: " + cause.getMessage());
    }

    private void resume(RunCheckpoint checkpoint, List<ApprovalRequest> requests,
                        Map<String, ApprovalDecision> decisions) {
        try {
            AgentId agentId = AgentId.of(requests.get(0).agentId());
            Optional<AraAgent> agent = agents.apply(agentId);
            if (agent.isEmpty()) {
                log.error("Cannot resume task [{}]: agent [{}] is not registered; its checkpoint is kept",
                        checkpoint.taskId(), agentId.value());
                return;
            }
            AgentResponse response = agent.get().execute(AgentTask.resuming(checkpoint, decisions));
            log.info("Resumed task [{}] ended in state {}", checkpoint.taskId(), response.finalState());
            resumedRuns.accept(response);
        } catch (RuntimeException e) {
            log.error("Resuming task [{}] failed: {}", checkpoint.taskId(), e.getMessage(), e);
        } finally {
            requests.forEach(request -> watching.remove(request.requestId()));
        }
    }
}
