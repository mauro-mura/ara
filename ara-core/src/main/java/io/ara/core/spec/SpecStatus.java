package io.ara.core.spec;

/**
 * Lifecycle phase of an {@link AgentSpec}, independent of its behavioural identity.
 *
 * <p>The vocabulary — {@code draft} / {@code shadow} / {@code canary} / {@code default}
 * / {@code deprecated} — is the one the meta-agent roadmap defined for exactly this type
 * (ADR-0065), and that ADR-0063 D4 already reuses for synthesized tools rather than
 * inventing a parallel set. Kept as empty records (not an enum) so the eventual
 * promotion pipeline (ADR-0083) can attach phase-specific data to a variant without
 * changing every {@code switch} — the same shape as {@code io.ara.core.hitl.ApprovalDecision}.
 *
 * <p>A status change never alters {@link SpecLineage#specHash()} or
 * {@link SpecLineage#specVersion()} (ADR-0065 D5): a spec evaluated in {@code draft} and
 * later promoted to {@code canary} is, by hash, the same object in a different phase.
 */
public sealed interface SpecStatus
        permits SpecStatus.Draft, SpecStatus.Shadow, SpecStatus.Canary,
                SpecStatus.Default, SpecStatus.Deprecated {

    /** Just created, not yet evaluated. The status of every {@code root(...)} and {@code derive(...)}. */
    record Draft() implements SpecStatus {}

    /** Running alongside the current default, receiving no live traffic — evaluated in the background. */
    record Shadow() implements SpecStatus {}

    /** Serving a small fraction of live traffic, under observation before full promotion. */
    record Canary() implements SpecStatus {}

    /** The spec currently serving all traffic for its task class. */
    record Default() implements SpecStatus {}

    /** Superseded; kept for lineage and audit, not for selection. */
    record Deprecated() implements SpecStatus {}
}
