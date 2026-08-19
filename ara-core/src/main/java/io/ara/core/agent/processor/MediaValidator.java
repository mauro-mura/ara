package io.ara.core.agent.processor;

import io.ara.core.media.MediaRef;

import java.util.List;
import java.util.Optional;

/**
 * Deterministic check applied to a task's media before {@code execute()} — the media-side
 * counterpart of {@link InputProcessor}.
 *
 * <h2>Why not just an InputProcessor</h2>
 * <p>{@link Processor#process(String)} sees only the input string, and media is a separate
 * component of the task: an input processor is structurally unable to look at an attachment.
 * Widening that signature to take the whole task would change the contract of every existing
 * processor, in and outside this repository, to serve a concern most of them never touch. A
 * separate phase in the same contract is the smaller change, and it keeps the two invariants
 * legible: input processors transform text, media validators only accept or reject.
 *
 * <h2>Reject only — no transformation</h2>
 * <p>{@link #validate} returns a reason or nothing; there is no way to return a modified media
 * list. That is deliberate: silently substituting or dropping an attachment is exactly the
 * failure mode ARA refuses everywhere else in the media path, because it produces a fluent
 * answer about a document the model never saw. A validator that objects must say so and stop
 * the task.
 *
 * <p>Declared in {@link io.ara.core.agent.AgentContract#mediaValidators()} and applied in
 * order by {@code ContractEnforcingAgent}, alongside the input processors.
 */
@FunctionalInterface
public interface MediaValidator {

    /**
     * Checks the task's media.
     *
     * @param media the task's attachments, in order; empty for a text-only task
     * @return {@link Optional#empty()} when the media is acceptable, or a human-readable
     *         reason for rejecting the task
     */
    Optional<String> validate(List<MediaRef> media);
}
