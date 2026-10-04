package io.ara.core.prompt;

/**
 * Lifecycle state of a {@link PromptCatalogEntry} or {@link io.ara.core.schema.SchemaCatalogEntry}.
 *
 * <p>Deliberately a four-state vocabulary — {@code draft} / {@code approved} /
 * {@code deprecated} / {@code archived} — and <em>not</em> the five-state
 * {@code draft/shadow/canary/default/deprecated} of {@link io.ara.core.spec.SpecStatus}.
 * A piece of catalog content has no natural notion of "serving 5% of traffic": it is either
 * approved for use or it is not. Forcing uniformity with the executable-spec lifecycle would
 * make two genuinely different concepts look alike only on the surface.
 *
 * <p>What moves an entry from {@link Draft} to {@link Approved} is the caller's policy, not
 * this type's: a human review, or an automated promotion step for content a system is allowed
 * to approve unattended.
 */
public sealed interface EntryStatus
        permits EntryStatus.Draft, EntryStatus.Approved,
                EntryStatus.Deprecated, EntryStatus.Archived {

    /** Being worked on; not loadable. */
    record Draft() implements EntryStatus {}

    /** Available for resolution at {@code createAgent()}. */
    record Approved() implements EntryStatus {}

    /** Superseded; still loadable by an agent that references it explicitly. */
    record Deprecated() implements EntryStatus {}

    /** Retired; no longer loadable. */
    record Archived() implements EntryStatus {}
}
