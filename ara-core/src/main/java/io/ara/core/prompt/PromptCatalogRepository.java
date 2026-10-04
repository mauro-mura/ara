package io.ara.core.prompt;

import java.util.Optional;

/**
 * Store of {@link PromptCatalogEntry} rows (ADR-0066 D2).
 *
 * <p>{@code tenantScopedId} is already in the {@code <tenant>:<promptCatalogId>} form
 * decided in ADR-0060 D2 — this interface inherits that scoping, it does not introduce
 * one of its own; to the repository the value is an opaque key.
 *
 * <p>Same null-object / in-memory-default idiom as the rest of ARA
 * ({@code SessionStore.inMemory()}, in-memory {@code ApprovalGate} / {@code CheckpointStore}) —
 * no new convention. Durable persistence beyond {@link #inMemory()} is an implementation
 * concern, not decided by the ADR.
 */
public interface PromptCatalogRepository {

    /**
     * The {@link EntryStatus.Approved} entry with this id and language, if one exists —
     * the lookup {@code CatalogPromptShaper} performs at {@code createAgent()}.
     */
    Optional<PromptCatalogEntry> findApproved(String tenantScopedId, String language);

    /** The entry with this exact {@code entryId}, whatever its status or language. */
    Optional<PromptCatalogEntry> findById(String entryId);

    /** Persists {@code entry} (keyed by its {@code entryId}), replacing any existing row with that id, and returns it. */
    PromptCatalogEntry save(PromptCatalogEntry entry);

    /** A process-local reference implementation — not durable across a JVM restart. */
    static PromptCatalogRepository inMemory() {
        return new InMemoryPromptCatalogRepository();
    }
}
