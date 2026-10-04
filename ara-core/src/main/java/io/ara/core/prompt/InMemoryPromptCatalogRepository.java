package io.ara.core.prompt;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link PromptCatalogRepository} backed by a {@link ConcurrentHashMap}
 * keyed by {@link PromptCatalogEntry#entryId()}. A reference implementation for tests and
 * single-process use; a durable backend implements the same interface.
 *
 * <p>Because {@link PromptCatalogEntry#derive} mints a new {@code entryId} for every
 * revision (ADR-0066 D4), distinct versions are distinct rows here, linked by
 * {@code derivedFromEntryId} rather than sharing a key.
 */
public final class InMemoryPromptCatalogRepository implements PromptCatalogRepository {

    private final ConcurrentHashMap<String, PromptCatalogEntry> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<PromptCatalogEntry> findApproved(String tenantScopedId, String language) {
        Objects.requireNonNull(tenantScopedId, "tenantScopedId must not be null");
        Objects.requireNonNull(language, "language must not be null");
        PromptCatalogEntry entry = byId.get(tenantScopedId);
        if (entry != null
                && entry.language().equals(language)
                && entry.status() instanceof EntryStatus.Approved) {
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    @Override
    public Optional<PromptCatalogEntry> findById(String entryId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(entryId, "entryId must not be null")));
    }

    @Override
    public PromptCatalogEntry save(PromptCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        byId.put(entry.entryId(), entry);
        return entry;
    }
}
