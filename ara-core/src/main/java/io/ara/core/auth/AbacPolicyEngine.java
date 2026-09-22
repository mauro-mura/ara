package io.ara.core.auth;

/**
 * Folds one or more {@link AbacPolicy} evaluations into a single {@link PolicyDecision}
 * (ADR-033): the combining step that turns individual rules into one verdict.
 *
 * <p>{@code io.ara.runtime.auth.CompositeAbacPolicyEngine} is the reference implementation
 * — a named, ordered list of policies combined under a chosen combining algorithm
 * (deny-overrides or permit-overrides).
 */
public interface AbacPolicyEngine {

    PolicyDecision evaluate(PolicyEvaluationContext ctx);
}
