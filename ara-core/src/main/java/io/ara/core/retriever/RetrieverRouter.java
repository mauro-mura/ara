package io.ara.core.retriever;

/**
 * Selects the appropriate {@link Retriever} for a given agent, by id.
 *
 * <p>Mirrors {@code io.ara.core.llm.LlmRouter}: instead of registering one
 * {@code RetrievalAugmentedStrategy} per knowledge domain, a single instance is
 * registered per delegate strategy (e.g. {@code "rag+react"}) and resolves the
 * concrete {@link Retriever} to use for each task from {@code AgentConfig.retrieverId()}
 * — the same way {@code LlmRouter} resolves the {@code LlmClient} from {@code
 * LlmConfig}. The default implementation in {@code ara-runtime} is {@code
 * DefaultRetrieverRouter}.
 */
public interface RetrieverRouter {

    /**
     * Selects and returns the {@link Retriever} to use for the next retrieval call.
     *
     * @param retrieverId the id requested by the agent ({@code AgentConfig.retrieverId()});
     *                     may be {@code null}, meaning "use the router's default"
     * @return the selected retriever; never {@code null}
     * @throws IllegalStateException if no suitable retriever can be resolved
     */
    Retriever select(String retrieverId);
}
