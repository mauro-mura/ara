package io.ara.core.schema;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link SchemaCatalogRepository} backed by a {@link ConcurrentHashMap} keyed
 * by {@link SchemaCatalogEntry#entryId()} — the schema twin of
 * {@code InMemoryPromptCatalogRepository}.
 *
 * <p>Because {@link SchemaCatalogEntry#derive} mints a new {@code entryId} for every
 * revision, distinct versions are distinct rows here, linked by
 * {@code derivedFromEntryId} rather than sharing a key.
 */
public final class InMemorySchemaCatalogRepository implements SchemaCatalogRepository {

    private final ConcurrentHashMap<String, SchemaCatalogEntry> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<SchemaCatalogEntry> findById(String entryId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(entryId, "entryId must not be null")));
    }

    @Override
    public SchemaCatalogEntry save(SchemaCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        byId.put(entry.entryId(), entry);
        return entry;
    }
}
