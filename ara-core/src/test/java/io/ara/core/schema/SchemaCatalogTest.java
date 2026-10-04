package io.ara.core.schema;

import io.ara.core.prompt.EntryStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schema catalog's data side: {@link SchemaCatalogEntry} and its in-memory repository. */
class SchemaCatalogTest {

    private static final String SCHEMA = """
            {"type":"object","required":["accountId"],"properties":{"accountId":{"type":"string"}}}""";

    @Test
    void anOriginalEntryStartsAtVersionOneAsADraftWithNoParent() {
        SchemaCatalogEntry entry = SchemaCatalogEntry.original("schema-1", SCHEMA);

        assertEquals(1, entry.contentVersion());
        assertInstanceOf(EntryStatus.Draft.class, entry.status());
        assertNull(entry.derivedFromEntryId());
        assertEquals(SCHEMA, entry.jsonSchema());
    }

    @Test
    void deriveMintsANewIdBumpsTheVersionAndPointsBack() {
        SchemaCatalogEntry v1 = SchemaCatalogEntry.original("schema-1", SCHEMA)
                .withStatus(new EntryStatus.Approved());
        SchemaCatalogEntry v2 = v1.derive("schema-2", "{\"type\":\"object\"}");

        assertEquals("schema-2", v2.entryId());
        assertEquals(2, v2.contentVersion());
        assertEquals("schema-1", v2.derivedFromEntryId());
        assertInstanceOf(EntryStatus.Draft.class, v2.status(), "a revision goes back to draft");
        assertInstanceOf(EntryStatus.Approved.class, v1.status(), "the parent is untouched");
    }

    @Test
    void withStatusKeepsIdVersionAndLineage() {
        SchemaCatalogEntry draft = SchemaCatalogEntry.original("schema-1", SCHEMA);
        SchemaCatalogEntry approved = draft.withStatus(new EntryStatus.Approved());

        assertEquals(draft.entryId(), approved.entryId());
        assertEquals(draft.contentVersion(), approved.contentVersion());
        assertEquals(draft.jsonSchema(), approved.jsonSchema());
    }

    @Test
    void constructionGuards() {
        assertThrows(NullPointerException.class, () -> SchemaCatalogEntry.original(null, SCHEMA));
        assertThrows(NullPointerException.class, () -> SchemaCatalogEntry.original("schema-1", null));
        assertThrows(IllegalArgumentException.class, () -> SchemaCatalogEntry.original("  ", SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> SchemaCatalogEntry.original("schema-1", "  "));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaCatalogEntry("schema-1", 0, new EntryStatus.Draft(), SCHEMA, null));
    }

    @Test
    void theRepositoryStoresRevisionsAsDistinctRows() {
        SchemaCatalogRepository catalog = SchemaCatalogRepository.inMemory();
        SchemaCatalogEntry v1 = catalog.save(SchemaCatalogEntry.original("schema-1", SCHEMA));
        SchemaCatalogEntry v2 = catalog.save(v1.derive("schema-2", "{\"type\":\"object\"}"));

        assertSame(v1.jsonSchema(), catalog.findById("schema-1").orElseThrow().jsonSchema());
        assertEquals(v2.jsonSchema(), catalog.findById("schema-2").orElseThrow().jsonSchema());
        assertTrue(catalog.findById("nope").isEmpty());
    }
}
