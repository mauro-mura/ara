package io.ara.core.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prompt catalog's data side: {@link PromptCatalogEntry} (one type, two uses),
 * the in-memory {@link PromptCatalogRepository}, mutation lineage and the four-state
 * {@link EntryStatus} vocabulary. Resolution into a system prompt is
 * {@code CatalogPromptShaper}'s, tested in {@code ara-runtime}.
 */
class PromptCatalogTest {

    // one type, two uses, kept honest by construction invariants
    @Test
    void fewShotEntry_requiresExampleOutput() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptCatalogEntry("id", PromptCatalogEntry.EntryType.FEW_SHOT_EXAMPLE,
                        "en", 1, new EntryStatus.Draft(), "input", null, null));
    }

    @Test
    void systemPromptEntry_rejectsExampleOutput() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptCatalogEntry("id", PromptCatalogEntry.EntryType.SYSTEM_PROMPT,
                        "en", 1, new EntryStatus.Draft(), "prompt", "unexpected", null));
    }

    @Test
    void entry_rejectsBlankIdAndSubOneVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptCatalogEntry.systemPrompt(" ", "en", "p"));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptCatalogEntry("id", PromptCatalogEntry.EntryType.SYSTEM_PROMPT,
                        "en", 0, new EntryStatus.Draft(), "p", null, null));
    }

    // mutation lineage, symmetric to SpecLineage.derivedFrom
    @Test
    void derive_bumpsVersionResetsStatusAndLinksParent() {
        PromptCatalogEntry v1 = PromptCatalogEntry.fewShot("few/base", "en", "in", "out")
                .withStatus(new EntryStatus.Approved());
        PromptCatalogEntry v2 = v1.derive("few/base@2", "in-better", "out-better");

        assertEquals(2, v2.contentVersion());
        assertEquals("few/base", v2.derivedFromEntryId());
        assertInstanceOf(EntryStatus.Draft.class, v2.status());
        assertEquals("out-better", v2.exampleOutput());
        assertEquals(PromptCatalogEntry.EntryType.FEW_SHOT_EXAMPLE, v2.entryType());
    }

    @Test
    void withStatus_changesOnlyStatus() {
        PromptCatalogEntry draft = PromptCatalogEntry.systemPrompt("acme:analyst", "en", "You are an analyst.");
        PromptCatalogEntry approved = draft.withStatus(new EntryStatus.Approved());

        assertEquals(draft.entryId(), approved.entryId());
        assertEquals(draft.contentVersion(), approved.contentVersion());
        assertEquals(draft.text(), approved.text());
        assertInstanceOf(EntryStatus.Approved.class, approved.status());
    }

    // in-memory repository: only Approved + matching language resolves
    @Test
    void repository_findApproved_filtersByStatusAndLanguage() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.systemPrompt("acme:analyst", "en", "draft text"));            // Draft
        repo.save(PromptCatalogEntry.systemPrompt("acme:writer", "en", "writer text")
                .withStatus(new EntryStatus.Approved()));
        repo.save(PromptCatalogEntry.systemPrompt("acme:analyst-it", "it", "testo")
                .withStatus(new EntryStatus.Approved()));

        assertTrue(repo.findApproved("acme:analyst", "en").isEmpty(), "Draft does not resolve");
        assertTrue(repo.findApproved("acme:writer", "fr").isEmpty(), "wrong language does not resolve");
        assertTrue(repo.findApproved("acme:writer", "en").isPresent());
        assertEquals("writer text", repo.findApproved("acme:writer", "en").orElseThrow().text());
        assertTrue(repo.findById("acme:analyst").isPresent(), "findById ignores status");
    }

    @Test
    void repository_save_replacesById() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.systemPrompt("acme:x", "en", "one"));
        repo.save(PromptCatalogEntry.systemPrompt("acme:x", "en", "two"));

        assertEquals("two", repo.findById("acme:x").orElseThrow().text());
    }

    // sanity: EntryStatus is exactly the four-state set, exhaustively switchable
    @Test
    void entryStatus_isTheFourStateVocabulary() {
        for (EntryStatus s : java.util.List.of(new EntryStatus.Draft(), new EntryStatus.Approved(),
                new EntryStatus.Deprecated(), new EntryStatus.Archived())) {
            String label = switch (s) {
                case EntryStatus.Draft d -> "draft";
                case EntryStatus.Approved a -> "approved";
                case EntryStatus.Deprecated d -> "deprecated";
                case EntryStatus.Archived a -> "archived";
            };
            assertTrue(label.length() > 3);
        }
        assertSame(EntryStatus.Approved.class, new EntryStatus.Approved().getClass());
    }

    // Tenant isolation: an entry saved under one tenant's scoped id never resolves for another
    // tenant's scoped id for the same base id.
    @Test
    void findApproved_neverResolvesAcrossTenantsForTheSameBaseId() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.systemPrompt("tenantA:support-analyst", "en",
                "Confidential: tenant A's pricing playbook.").withStatus(new EntryStatus.Approved()));

        assertTrue(repo.findApproved("tenantB:support-analyst", "en").isEmpty(),
                "tenant B must not resolve a system prompt only tenant A has approved");
        assertEquals("Confidential: tenant A's pricing playbook.",
                repo.findApproved("tenantA:support-analyst", "en").orElseThrow().text());
    }

    @Test
    void bothTenantsCanApproveTheSameBaseId_eachResolvesOnlyItsOwn() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.systemPrompt("tenantA:support-analyst", "en", "A's prompt.")
                .withStatus(new EntryStatus.Approved()));
        repo.save(PromptCatalogEntry.systemPrompt("tenantB:support-analyst", "en", "B's prompt.")
                .withStatus(new EntryStatus.Approved()));

        assertEquals("A's prompt.", repo.findApproved("tenantA:support-analyst", "en").orElseThrow().text());
        assertEquals("B's prompt.", repo.findApproved("tenantB:support-analyst", "en").orElseThrow().text());
    }
}
