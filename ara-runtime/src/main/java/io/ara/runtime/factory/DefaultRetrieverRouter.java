package io.ara.runtime.factory;

import io.ara.core.retriever.Retriever;
import io.ara.core.retriever.RetrieverRouter;

import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link RetrieverRouter}.
 *
 * <p>Resolves the {@link Retriever} to use by looking up {@code retrieverId} in a
 * fixed registry of named retrievers, falling back to the configured default id when
 * {@code retrieverId} is {@code null} or not found — mirrors {@code DefaultLlmRouter}'s
 * fallback-to-default behaviour for LLM clients.
 */
public final class DefaultRetrieverRouter implements RetrieverRouter {

    private final Map<String, Retriever> registry;
    private final String                 defaultId;

    public DefaultRetrieverRouter(Map<String, Retriever> registry, String defaultId) {
        this.registry  = Map.copyOf(Objects.requireNonNull(registry, "registry must not be null"));
        this.defaultId = Objects.requireNonNull(defaultId, "defaultId must not be null");
    }

    @Override
    public Retriever select(String retrieverId) {
        if (retrieverId != null) {
            Retriever named = registry.get(retrieverId);
            if (named != null) return named;
        }

        Retriever def = registry.get(defaultId);
        if (def != null) return def;

        throw new IllegalStateException(
                "No Retriever found for id='" + retrieverId
                + "' and no default registered (defaultId='" + defaultId + "')");
    }
}
