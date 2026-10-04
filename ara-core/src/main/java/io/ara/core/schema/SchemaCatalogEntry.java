package io.ara.core.schema;

import io.ara.core.prompt.EntryStatus;

import java.util.Objects;

/**
 * One versioned, resolvable JSON Schema — the content behind an {@code AgentSpec.schemaRef} /
 * {@code outputSchemaRef} and, once resolved, an agent's {@code AgentContract} input or
 * output schema.
 *
 * <p>Deliberately the same shape as {@link io.ara.core.prompt.PromptCatalogEntry} minus the
 * fields that are prompt-specific ({@code entryType}, {@code language},
 * {@code exampleOutput}): identity, version, status, content, and a back-pointer to the
 * entry a revision was derived from. Content is versioned <em>independently of</em> the spec
 * that references it, so a spec's behavioural hash can stay a pure function of its
 * {@code AgentConfig} while the referenced content still has a lineage of its own.
 *
 * <p>{@link EntryStatus} is shared with the prompt catalog rather than duplicated: its four
 * states are about catalog content in general ("approved for use or not"), with nothing
 * prompt-specific in the type.
 *
 * @param entryId            stable identity; the value {@code AgentSpec.schemaRef} holds
 * @param contentVersion     1 for an original entry, {@code parent.contentVersion + 1} after {@link #derive}
 * @param status             {@link EntryStatus}; a fresh entry is {@link EntryStatus.Draft}
 * @param jsonSchema         the JSON Schema text itself
 * @param derivedFromEntryId {@code entryId} of the parent entry, or {@code null} for an original
 */
public record SchemaCatalogEntry(
        String      entryId,
        int         contentVersion,
        EntryStatus status,
        String      jsonSchema,
        String      derivedFromEntryId
) {

    public SchemaCatalogEntry {
        Objects.requireNonNull(entryId, "entryId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(jsonSchema, "jsonSchema must not be null");
        if (entryId.isBlank()) {
            throw new IllegalArgumentException("entryId must not be blank");
        }
        if (jsonSchema.isBlank()) {
            throw new IllegalArgumentException("jsonSchema must not be blank");
        }
        if (contentVersion < 1) {
            throw new IllegalArgumentException("contentVersion must be >= 1, got: " + contentVersion);
        }
    }

    /** An original entry: version 1, {@link EntryStatus.Draft}, no parent. */
    public static SchemaCatalogEntry original(String entryId, String jsonSchema) {
        return new SchemaCatalogEntry(entryId, 1, new EntryStatus.Draft(), jsonSchema, null);
    }

    /**
     * A revision of this entry: a fresh {@code entryId}, {@code contentVersion + 1}, status
     * back to {@link EntryStatus.Draft}, {@code derivedFromEntryId} pointing at this entry
     * — the same per-entry lineage ADR-0066 D4 defined for prompt content.
     */
    public SchemaCatalogEntry derive(String newEntryId, String newJsonSchema) {
        return new SchemaCatalogEntry(newEntryId, contentVersion + 1, new EntryStatus.Draft(),
                newJsonSchema, entryId);
    }

    /** A copy with only {@link #status} replaced — same id, same version, same lineage. */
    public SchemaCatalogEntry withStatus(EntryStatus newStatus) {
        return new SchemaCatalogEntry(entryId, contentVersion, newStatus, jsonSchema, derivedFromEntryId);
    }
}
