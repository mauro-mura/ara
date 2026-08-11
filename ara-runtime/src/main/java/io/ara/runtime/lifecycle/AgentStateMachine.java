package io.ara.runtime.lifecycle;

import io.ara.core.agent.AgentState;
import io.ara.core.common.AgentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe finite state machine that governs the lifecycle of a single
 * {@link io.ara.core.agent.AraAgent} instance.
 *
 * <p>Transitions are validated against a static allowance table derived from the
 * ARA architecture specification. Any attempt to perform an invalid transition
 * throws an {@link IllegalStateException} with a descriptive message.
 *
 * <p>State reads and writes are lock-free: the implementation uses an
 * {@link AtomicReference} with a compare-and-set loop, which is safe even when
 * the agent's virtual thread and an administrative thread (e.g. the Meta-Agent)
 * concurrently attempt transitions.
 */
public final class AgentStateMachine {

    private static final Logger log = LoggerFactory.getLogger(AgentStateMachine.class);

    /** Valid target states for each source state, per the ARA state diagram. */
    private static final Map<AgentState, Set<AgentState>> ALLOWED_TRANSITIONS;

    static {
        Map<AgentState, Set<AgentState>> m = new EnumMap<>(AgentState.class);
        m.put(AgentState.IDLE,      Set.of(AgentState.PLANNING));
        m.put(AgentState.PLANNING,  Set.of(AgentState.EXECUTING, AgentState.FAILED));
        m.put(AgentState.EXECUTING, Set.of(AgentState.DONE, AgentState.FAILED));
        m.put(AgentState.DONE,      Set.of(AgentState.IDLE));
        m.put(AgentState.FAILED,    Set.of(AgentState.IDLE));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private final AgentId agentId;
    private final AtomicReference<AgentState> current;

    /**
     * Creates a new state machine for the given agent, starting in {@link AgentState#IDLE}.
     *
     * @param agentId the agent this machine belongs to; used only in log and error messages
     */
    public AgentStateMachine(AgentId agentId) {
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        this.current = new AtomicReference<>(AgentState.IDLE);
    }

    /**
     * Returns the current state.
     *
     * @return the current {@link AgentState}; never {@code null}
     */
    public AgentState current() {
        return current.get();
    }

    /**
     * Attempts to transition from the current state to {@code target}.
     *
     * <p>Uses a CAS loop to remain correct when two threads race to transition
     * the same agent simultaneously.
     *
     * @param target the desired next state
     * @throws IllegalStateException if the transition is not allowed from the current state
     */
    public void transitionTo(AgentState target) {
        Objects.requireNonNull(target, "target state must not be null");

        AgentState prev;
        do {
            prev = current.get();
            Set<AgentState> allowed = ALLOWED_TRANSITIONS.getOrDefault(prev, Set.of());
            if (!allowed.contains(target)) {
                throw new IllegalStateException(
                        "Agent [%s]: invalid transition %s → %s. Allowed targets from %s: %s"
                                .formatted(agentId.value(), prev, target, prev, allowed));
            }
        } while (!current.compareAndSet(prev, target));

        log.debug("Agent [{}] transitioned {} → {}", agentId.value(), prev, target);
    }

    /**
     * Returns {@code true} if the current state is one of the given states.
     *
     * @param states the states to check
     * @return {@code true} if the current state matches any of the provided states
     */
    public boolean isIn(AgentState... states) {
        AgentState c = current.get();
        for (AgentState s : states) {
            if (c == s) return true;
        }
        return false;
    }

    /**
     * Asserts that the current state equals {@code expected}.
     *
     * @param expected the required current state
     * @throws IllegalStateException if the current state is not {@code expected}
     */
    public void requireState(AgentState expected) {
        AgentState c = current.get();
        if (c != expected) {
            throw new IllegalStateException(
                    "Agent [%s]: expected state %s but current state is %s"
                            .formatted(agentId.value(), expected, c));
        }
    }
}