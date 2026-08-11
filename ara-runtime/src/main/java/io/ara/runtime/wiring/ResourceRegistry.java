package io.ara.runtime.wiring;

import java.util.function.Supplier;

/**
 * Shared catalog of ref-counted, versioned resources, keyed by a stable id (ADR-039 §4).
 *
 * <p>A {@code publish} does not mutate an existing entry — it publishes a new version.
 * A {@code pin} hands out a {@link Lease} on a specific version (explicit pin) or on
 * whichever version is current at pin time (follow-latest, the default). The registry
 * is the <strong>only</strong> place reference-counting/closing logic exists in the
 * whole mechanism (ADR-039 "Guardrail di semplicità").
 *
 * <p>Resources are released via a caller-supplied closing action (see {@link
 * DefaultResourceRegistry}) rather than a type bound: some resources this mechanism
 * manages implement {@link AutoCloseable} (e.g. {@code McpClient}), others (e.g. {@code
 * LlmClient}) do not and need no real teardown — the registry does not force either
 * shape on {@code R}.
 *
 * @param <S> the immutable spec a version is built from (e.g. an {@code LlmProfile})
 * @param <R> the resource type the spec resolves to
 */
public interface ResourceRegistry<S, R> {

    /**
     * Leases the version that is current for {@code id} at the moment of the call
     * (follow-latest-at-pin-time — ADR-039 §4 default).
     *
     * @throws IllegalStateException if no version has ever been published for {@code id}
     */
    Lease<R> pin(String id);

    /**
     * Leases a specific, explicit version of {@code id} — the opt-in path for
     * reproducibility/rollback (ADR-039 §4).
     *
     * @throws IllegalStateException if that version is unknown or has already been
     *                                fully closed (no lease ever kept its ref-count above zero)
     */
    Lease<R> pin(String id, long version);

    /**
     * Leases the current version of {@code id}, publishing it first via {@code
     * specIfAbsent} if {@code id} has never been published. The whole check-then-publish
     * happens under this id's lock, so concurrent first-time callers for the same
     * never-seen id never race into building the resource twice. Intended for
     * content-addressed ids (e.g. an inline LLM transport override) that are never
     * explicitly {@code publish}ed ahead of time.
     */
    Lease<R> pinOrCreate(String id, Supplier<S> specIfAbsent);

    /** Equivalent to {@code publish(id, spec, DrainPolicy.GRACEFUL)}. */
    long publish(String id, S spec);

    /**
     * Builds a new resource from {@code spec} and installs it as the current version for
     * {@code id}, retiring whatever was current before. The retired version's resource is
     * closed once its ref-count reaches zero; {@code policy} controls whether leases still
     * held on it are left alone ({@link DrainPolicy.Graceful}) or forcibly evicted ({@link
     * DrainPolicy.Forced}).
     *
     * @return the new version number
     */
    long publish(String id, S spec, DrainPolicy policy);
}
