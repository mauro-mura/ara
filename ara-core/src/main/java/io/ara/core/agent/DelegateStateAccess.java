package io.ara.core.agent;

/**
 * Governs what a delegated sub-agent (via {@code AgentDelegationTool}) sees of and can
 * do to its caller's {@link RunState}. Set on the delegating agent's {@link
 * ExecutionConfig#delegateStateAccess()} — the caller decides what it grants, never the
 * delegate.
 */
public enum DelegateStateAccess {

    /** The delegate shares the caller's {@link RunState} object by reference: reads and writes are mutually visible. */
    SHARED,

    /** The delegate reads the caller's state but its own writes land on a private layer the caller never sees. Default. */
    OVERLAY,

    /** The delegate starts with empty state and can neither see nor affect the caller's. */
    ISOLATED
}
