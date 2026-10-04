package io.ara.core.prompt;

import java.util.Objects;

/**
 * One versioned, resolvable piece of prompt content — either an agent's system prompt or
 * a single few-shot example — with a lineage back to the entry it was derived from.
 *
 * <p>One type, two uses, told apart by {@link #entryType()}: both share identity,
 * language, version, status and lineage; the only real difference is whether
 * {@link #exampleOutput()} is populated. Two parallel types for one field's worth of
 * difference would be a proliferation of the same kind ADR-0056 exists to prevent, applied to
 * a data type instead of an agent.
 *
 * <p>This is the content an {@code AgentIdentity.promptCatalogId} and an
 * {@code AgentSpec.fewShotRefs} entry point at; {@link
 * io.ara.runtime.prompt.CatalogPromptShaper} (in {@code ara-runtime}) resolves the former at
 * {@code createAgent()} time.
 *
 * @param entryId            stable identity of this entry. For a {@code SYSTEM_PROMPT}
 *                           entry this doubles as the value an agent's
 *                           {@code AgentIdentity.promptCatalogId} resolves against
 *                           (already tenant-scoped as {@code <tenant>:<id>}, ADR-0060 D2);
 *                           for a {@code FEW_SHOT_EXAMPLE} it is what
 *                           {@code AgentSpec.fewShotRefs} holds.
 * @param entryType          {@link EntryType#SYSTEM_PROMPT} or {@link EntryType#FEW_SHOT_EXAMPLE}
 * @param language           BCP-47-ish language tag; resolution matches on it
 * @param contentVersion     1 for an original entry, {@code parent.contentVersion + 1} for one produced by {@link #derive}
 * @param status             {@link EntryStatus}; only {@link EntryStatus.Approved} entries resolve at {@code createAgent()}
 * @param text               for {@code SYSTEM_PROMPT}: the prompt; for {@code FEW_SHOT_EXAMPLE}: the example's input
 * @param exampleOutput      {@code null} for {@code SYSTEM_PROMPT}; the expected output for {@code FEW_SHOT_EXAMPLE}
 * @param derivedFromEntryId {@code entryId} of the parent entry, or {@code null} for an original — symmetric to
 *                           {@code SpecLineage.derivedFrom}, one granularity down
 */
public record PromptCatalogEntry(
        String      entryId,
        EntryType   entryType,
        String      language,
        int         contentVersion,
        EntryStatus status,
        String      text,
        String      exampleOutput,
        String      derivedFromEntryId
) {

    public enum EntryType { SYSTEM_PROMPT, FEW_SHOT_EXAMPLE }

    public PromptCatalogEntry {
        Objects.requireNonNull(entryId, "entryId must not be null");
        Objects.requireNonNull(entryType, "entryType must not be null");
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(text, "text must not be null");
        if (entryId.isBlank()) {
            throw new IllegalArgumentException("entryId must not be blank");
        }
        if (language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        if (contentVersion < 1) {
            throw new IllegalArgumentException("contentVersion must be >= 1, got: " + contentVersion);
        }
        if (entryType == EntryType.FEW_SHOT_EXAMPLE && exampleOutput == null) {
            throw new IllegalArgumentException("a FEW_SHOT_EXAMPLE entry requires exampleOutput");
        }
        if (entryType == EntryType.SYSTEM_PROMPT && exampleOutput != null) {
            throw new IllegalArgumentException("a SYSTEM_PROMPT entry must not carry exampleOutput");
        }
    }

    /** An original system-prompt entry: version 1, {@link EntryStatus.Draft}, no parent. */
    public static PromptCatalogEntry systemPrompt(String entryId, String language, String text) {
        return new PromptCatalogEntry(entryId, EntryType.SYSTEM_PROMPT, language, 1,
                new EntryStatus.Draft(), text, null, null);
    }

    /** An original few-shot entry: version 1, {@link EntryStatus.Draft}, no parent. */
    public static PromptCatalogEntry fewShot(String entryId, String language, String input, String expectedOutput) {
        return new PromptCatalogEntry(entryId, EntryType.FEW_SHOT_EXAMPLE, language, 1,
                new EntryStatus.Draft(), input, Objects.requireNonNull(expectedOutput, "expectedOutput must not be null"), null);
    }

    /**
     * A revision of this entry (ADR-0066 D4): a fresh {@code entryId}, {@code contentVersion + 1},
     * status back to {@link EntryStatus.Draft}, {@code derivedFromEntryId} pointing at this entry.
     * {@code newExampleOutput} is ignored for a {@code SYSTEM_PROMPT} and required for a
     * {@code FEW_SHOT_EXAMPLE}.
     */
    public PromptCatalogEntry derive(String newEntryId, String newText, String newExampleOutput) {
        String output = entryType == EntryType.FEW_SHOT_EXAMPLE ? newExampleOutput : null;
        return new PromptCatalogEntry(newEntryId, entryType, language, contentVersion + 1,
                new EntryStatus.Draft(), newText, output, entryId);
    }

    /** A copy with only {@link #status} replaced — same id, same version, same lineage. */
    public PromptCatalogEntry withStatus(EntryStatus newStatus) {
        return new PromptCatalogEntry(entryId, entryType, language, contentVersion,
                newStatus, text, exampleOutput, derivedFromEntryId);
    }
}
