package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentFuture;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.common.AgentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Fans a task out to N member agents running concurrently on {@code executor}, then merges
 * their responses into one via {@link AgentChain.MergeStrategy} and {@link
 * AgentChain.FailurePolicy}.
 *
 * <p>A plain {@link AraAgent} like any other — the fan-out is an implementation detail
 * invisible to callers. In particular it composes for free with everything {@code AraAgent}
 * already composes with: usable as a single {@link AgentPipeline} step (see {@link
 * AgentPipeline.Builder#parallel}), as an {@code AgentPipeline.FsmBuilder} state, nested
 * inside another {@code ParallelAgent} for a tree of fan-outs, registered in {@code
 * AgentRegistry}, or delegated to via {@code AgentDelegationTool}.
 *
 * <p>Every member receives the identical {@link AgentTask} — {@code attachments()},
 * {@code sessionId()}, and {@code runContext()} (including {@code RunState}, ADR-041) reach
 * every member unchanged, exactly as a sequential pipeline step would receive them. A member
 * that writes to {@code runContext().state()} races with every other member doing the same;
 * {@link io.ara.core.agent.RunState#merge} is the safe primitive for that, plain {@code put}
 * is not.
 *
 * <p>{@link #currentState()} always reports {@link AgentState#IDLE}: this agent owns no
 * session-scoped lifecycle of its own, and does not gate concurrent {@link #execute} calls
 * behind a single shared state field. An earlier, hand-rolled pipeline-as-agent adapter in
 * this codebase did exactly that with one {@code volatile AgentState} field and threw {@code
 * IllegalStateException("not IDLE")} on any second concurrent call — including one for a
 * completely different session — which is precisely the bug this class avoids by not tracking
 * state at all. Each member agent's own lifecycle (e.g. a real {@code AgentInstance}'s
 * per-session state machine) is what actually matters here.
 */
public final class ParallelAgent implements AraAgent {

    private static final Logger log = LoggerFactory.getLogger(ParallelAgent.class);

    private final AgentId agentId;
    private final AgentConfig config;
    private final List<AraAgent> members;
    private final Executor executor;
    private final AgentChain.MergeStrategy merge;
    private final AgentChain.FailurePolicy failurePolicy;

    public ParallelAgent(AgentId agentId, List<AraAgent> members, Executor executor,
                         AgentChain.MergeStrategy merge, AgentChain.FailurePolicy failurePolicy) {
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(members, "members must not be null");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("ParallelAgent requires at least one member");
        }
        this.members = List.copyOf(members);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.merge = Objects.requireNonNull(merge, "merge must not be null");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        this.config = AgentConfig.defaults().agentId(agentId).agentType("parallel").build();
    }

    /** Convenience: {@link AgentChain.FailurePolicy#FAIL_FAST}. */
    public ParallelAgent(AgentId agentId, List<AraAgent> members, Executor executor,
                         AgentChain.MergeStrategy merge) {
        this(agentId, members, executor, merge, AgentChain.FailurePolicy.FAIL_FAST);
    }

    @Override public AgentId     agentId()      { return agentId; }
    @Override public AgentConfig config()       { return config; }
    @Override public AgentState  currentState() { return AgentState.IDLE; }

    /** Terminates every member. Best-effort: one member's failure does not stop the rest. */
    @Override
    public void terminate() {
        for (AraAgent member : members) {
            try {
                member.terminate();
            } catch (RuntimeException e) {
                log.warn("ParallelAgent [{}] failed to terminate member [{}] — continuing",
                        agentId.value(), member.agentId().value(), e);
            }
        }
    }

    @Override
    public AgentResponse execute(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        log.debug("ParallelAgent [{}] fanning out to {} member(s) for task [{}]",
                agentId.value(), members.size(), task.taskId());
        try {
            List<AgentFuture> futures = members.stream()
                    .map(member -> AraAgents.executeAsync(member, task, executor))
                    .toList();
            return AgentFuture.allOf(futures, merge, failurePolicy).get();
        } catch (RuntimeException e) {
            log.warn("ParallelAgent [{}] fan-out failed", agentId.value(), e);
            return AgentResponse.failure(task.taskId(), agentId,
                    "Parallel fan-out failed: " + e.getMessage(), Duration.ZERO);
        }
    }
}
