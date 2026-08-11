package io.ara.runtime.agent;

import io.ara.core.agent.RunState;
import io.ara.core.agent.SessionBusyPolicy;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.common.AgentId;
import io.ara.core.memory.MemoryManager;
import io.ara.runtime.lifecycle.AgentStateMachine;
import io.ara.runtime.wiring.AgentWiring;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Isolated execution context for a single agent session.
 *
 * <p>Each {@link SessionId} corresponds to exactly one {@code AgentSession}.
 * The session owns the {@link AgentStateMachine} and the {@link MemoryManager}
 * — both exclusive to the session, not shared between concurrent sessions.
 *
 * <p>{@code AgentInstance} is stateless with respect to execution: multiple sessions
 * can be active concurrently on the same agent without interfering.
 *
 * <p>Since ADR-039, each session also pins an {@link AgentWiring} snapshot — the config
 * and resolved collaborators (LLM client, tool registry) it was born with — for its
 * entire lifetime. A {@code reconfigure} on the owning agent never touches an existing
 * session's wiring; only sessions created afterward see the new configuration.
 */
public final class AgentSession {

    private final SessionId sessionId;
    private final AgentStateMachine stateMachine;
    private final MemoryManager memoryManager;
    private final ConversationHistory conversationHistory;
    private final AgentWiring wiring;
    /** Session-scoped shared working state (Agno {@code session_state} parity) — see {@link RunState}. */
    private final RunState runState;

    /**
     * Serializes tasks that target this same session. Fairness is bought only where it buys
     * something: under {@link SessionBusyPolicy#ENQUEUE} a fair lock is what makes the queue
     * FIFO instead of barge-ordered. Under {@link SessionBusyPolicy#REJECT} (the default)
     * every acquisition goes through {@code tryLock()}, which barges past the queue on a
     * fair lock anyway — so fairness would change nothing observable while still paying the
     * fair lock's slower uncontended acquire on every single task.
     */
    private final ReentrantLock executionLock;
    /** Cooperative per-session cancellation flag, consumed at task/iteration boundaries. */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /** The thread currently executing a task on this session, if any (for interrupt-based cancel). */
    private final AtomicReference<Thread> runningThread = new AtomicReference<>();

    AgentSession(SessionId sessionId, MemoryManager memoryManager, AgentWiring wiring, SessionStore store) {
        this.sessionId           = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.memoryManager       = Objects.requireNonNull(memoryManager, "memoryManager must not be null");
        this.wiring              = Objects.requireNonNull(wiring, "wiring must not be null");
        Objects.requireNonNull(store, "store must not be null");
        this.executionLock       = new ReentrantLock(
                wiring.config().sessionBusyPolicy() == SessionBusyPolicy.ENQUEUE);
        this.conversationHistory = new ConversationHistory(store, sessionId);
        this.runState            = RunState.persisting(store, sessionId);
        // Create a per-session state machine using the session id as the agent id label
        this.stateMachine  = new AgentStateMachine(AgentId.of("session:" + sessionId.value()));
    }

    public SessionId sessionId()                         { return sessionId; }
    public AgentStateMachine stateMachine()              { return stateMachine; }
    public MemoryManager memoryManager()                 { return memoryManager; }
    public ConversationHistory conversationHistory()     { return conversationHistory; }
    /** The config and resolved collaborators this session was born with — pinned for its whole lifetime. */
    public AgentWiring wiring()                          { return wiring; }
    /** This session's shared working state — same instance across every task run on this session. */
    public RunState runState()                            { return runState; }

    // ── Concurrency / cancellation (ADR-016) ────────────────────────────────

    /** The per-session execution lock. Serializes concurrent tasks on this session. */
    public ReentrantLock executionLock() { return executionLock; }

    /**
     * Returns {@code true} if a task is currently executing on (or queued against) this
     * session. Used by the TTL sweeper to skip a session it must not tear down: releasing
     * an in-flight session's {@link AgentWiring} leases would pull the LLM transport out
     * from under a running execution.
     */
    public boolean isBusy() { return executionLock.isLocked(); }

    /**
     * Requests cooperative cancellation of the task currently running on this session
     * and interrupts its thread (if any) to unblock I/O. Idempotent.
     */
    public void requestCancel() {
        cancelRequested.set(true);
        Thread t = runningThread.get();
        if (t != null) t.interrupt();
    }

    /** Returns {@code true} if cancellation has been requested for this session. */
    public boolean isCancelRequested() { return cancelRequested.get(); }

    /** Clears the cancellation flag (called when a new task starts on this session). */
    public void clearCancel() { cancelRequested.set(false); }

    /** Records the thread currently executing a task on this session ({@code null} to clear). */
    public void setRunningThread(Thread t) { runningThread.set(t); }
}
