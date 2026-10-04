package io.ara.runtime.prompt;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.processor.PromptShaper;
import io.ara.core.prompt.PromptCatalogEntry;
import io.ara.core.prompt.PromptCatalogRepository;

import java.util.Map;
import java.util.Objects;

/**
 * {@link PromptShaper} for the {@code promptCatalogId} resolution rule: when an agent carries
 * a {@code promptCatalogId}, its system prompt comes from the catalog rather than the inline
 * {@code AgentConfig.systemPrompt()}.
 *
 * <p>{@link #shape} therefore <b>replaces</b> the incoming {@code systemPrompt} with the
 * approved {@code SYSTEM_PROMPT} catalog entry's text, after {@code {placeholder}}
 * substitution from {@link #variables} — deterministic, no LLM call, exactly like every
 * other {@code PromptShaper}. It is only ever wired in when a {@code promptCatalogId} is
 * set, so replacing rather than appending is the rule, not a surprise.
 *
 * <p>A missing or non-{@code SYSTEM_PROMPT} resolution is a hard failure, not a silent
 * fall-through to the inline prompt: an agent that asked for a catalog prompt and did not
 * get one should fail loudly at {@code createAgent()} time.
 */
public final class CatalogPromptShaper implements PromptShaper {

    private final PromptCatalogRepository repository;
    private final String tenantScopedCatalogId;
    private final String language;
    private final Map<String, String> variables;

    private CatalogPromptShaper(PromptCatalogRepository repository, String tenantScopedCatalogId,
                                String language, Map<String, String> variables) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.tenantScopedCatalogId = Objects.requireNonNull(tenantScopedCatalogId, "tenantScopedCatalogId must not be null");
        this.language = Objects.requireNonNull(language, "language must not be null");
        this.variables = Map.copyOf(Objects.requireNonNullElse(variables, Map.of()));
    }

    /**
     * @param repository            where to resolve the entry from
     * @param tenantScopedCatalogId the agent's {@code promptCatalogId}, tenant-prefixed as
     *                              {@code <tenant>:<id>} (ADR-0060 D2)
     * @param language              language tag to match
     * @param variables             {@code {key}} → value substitutions applied to the resolved text
     */
    public static CatalogPromptShaper of(PromptCatalogRepository repository, String tenantScopedCatalogId,
                                         String language, Map<String, String> variables) {
        return new CatalogPromptShaper(repository, tenantScopedCatalogId, language, variables);
    }

    @Override
    public String shape(String systemPrompt, AgentTask task) {
        PromptCatalogEntry entry = repository.findApproved(tenantScopedCatalogId, language)
                .orElseThrow(() -> new IllegalStateException(
                        "no approved catalog entry for '" + tenantScopedCatalogId + "' [" + language + "]"));
        if (entry.entryType() != PromptCatalogEntry.EntryType.SYSTEM_PROMPT) {
            throw new IllegalStateException(
                    "'" + tenantScopedCatalogId + "' resolves to a " + entry.entryType()
                            + " entry; a promptCatalogId must resolve to SYSTEM_PROMPT");
        }
        return applyVariables(entry.text());
    }

    private String applyVariables(String text) {
        String result = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            result = result.replace("{" + variable.getKey() + "}", variable.getValue());
        }
        return result;
    }
}
