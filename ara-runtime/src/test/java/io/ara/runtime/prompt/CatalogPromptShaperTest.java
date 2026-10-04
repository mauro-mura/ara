package io.ara.runtime.prompt;

import io.ara.core.agent.AgentTask;
import io.ara.core.prompt.EntryStatus;
import io.ara.core.prompt.PromptCatalogEntry;
import io.ara.core.prompt.PromptCatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CatalogPromptShaper}: resolves the approved SYSTEM_PROMPT entry and applies variables. */
class CatalogPromptShaperTest {

    private static final AgentTask ANY_TASK = AgentTask.of("hello");

    @Test
    void resolvesApprovedSystemPromptAndSubstitutesVariables() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.systemPrompt("acme:analyst", "en",
                "You are a {role}. Today is {date}.").withStatus(new EntryStatus.Approved()));

        CatalogPromptShaper shaper = CatalogPromptShaper.of(repo, "acme:analyst", "en",
                Map.of("role", "financial analyst", "date", "2026-09-03"));

        assertEquals("You are a financial analyst. Today is 2026-09-03.",
                shaper.shape("IGNORED inline prompt", ANY_TASK));
    }

    @Test
    void failsLoudlyWhenNothingResolves() {
        CatalogPromptShaper shaper = CatalogPromptShaper.of(
                PromptCatalogRepository.inMemory(), "acme:missing", "en", Map.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> shaper.shape("x", ANY_TASK));
        assertTrue(ex.getMessage().contains("acme:missing"));
    }

    @Test
    void rejectsAnIdThatResolvesToAFewShotEntry() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.fewShot("acme:oops", "en", "in", "out")
                .withStatus(new EntryStatus.Approved()));

        CatalogPromptShaper shaper = CatalogPromptShaper.of(repo, "acme:oops", "en", Map.of());
        assertThrows(IllegalStateException.class, () -> shaper.shape("x", ANY_TASK));
    }

    @Test
    void isImmutableToTheVariablesMapPassedIn() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.systemPrompt("acme:x", "en", "hi {n}")
                .withStatus(new EntryStatus.Approved()));
        Map<String, String> vars = new HashMap<>(Map.of("n", "one"));
        CatalogPromptShaper shaper = CatalogPromptShaper.of(repo, "acme:x", "en", vars);
        vars.put("n", "two");

        assertEquals("hi one", shaper.shape("x", ANY_TASK));
    }

    // An agent provisioned for tenant B resolving the same base id must fail loudly rather
    // than silently inherit tenant A's prompt content.
    @Test
    void neverLeaksATenantsSystemPromptToAnothersAgent() {
        PromptCatalogRepository repo = PromptCatalogRepository.inMemory();
        repo.save(PromptCatalogEntry.systemPrompt("tenantA:support-analyst", "en",
                "You know tenant A's internal escalation contacts.").withStatus(new EntryStatus.Approved()));

        CatalogPromptShaper shaper = CatalogPromptShaper.of(repo, "tenantB:support-analyst", "en", Map.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> shaper.shape("ignored", ANY_TASK));
        assertTrue(ex.getMessage().contains("tenantB:support-analyst"));
    }
}
