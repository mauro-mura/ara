package io.ara.core.schema;

import java.util.Optional;

/**
 * Store of {@link SchemaCatalogEntry} rows — the schema-side twin of
 * {@code PromptCatalogRepository} (ADR-0066 D2), and what an {@code AgentSpec.schemaRef}
 * resolves against.
 *
 * <p>Same null-object / in-memory-default idiom as the rest of ARA
 * ({@code SessionStore.inMemory()}, {@code PromptCatalogRepository.inMemory()},
 * {@code TraceStore.inMemory()}) — no new convention. Durable persistence behind the same
 * interface is an implementation concern.
 */
public interface SchemaCatalogRepository {

    /** The entry with this exact {@code entryId}, whatever its status. */
    Optional<SchemaCatalogEntry> findById(String entryId);

    /** Persists {@code entry} (keyed by its {@code entryId}), replacing any row with that id, and returns it. */
    SchemaCatalogEntry save(SchemaCatalogEntry entry);

    /** A process-local reference implementation — not durable across a JVM restart. */
    static SchemaCatalogRepository inMemory() {
        return new InMemorySchemaCatalogRepository();
    }
}
