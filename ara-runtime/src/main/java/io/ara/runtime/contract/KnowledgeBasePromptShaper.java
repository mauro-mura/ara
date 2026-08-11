package io.ara.runtime.contract;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.processor.PromptShaper;

/**
 * Appends knowledge-base usage instructions to the system prompt when a KB is attached.
 *
 * <p>Applied automatically by {@link io.ara.runtime.factory.AgentFactory} when
 * {@code config.knowledgeBaseId()} is non-blank and {@code "search_documents"} is
 * in the agent's enabled tool set.
 */
public final class KnowledgeBasePromptShaper implements PromptShaper {

    private final AgentConfig config;

    public KnowledgeBasePromptShaper(AgentConfig config) {
        this.config = config;
    }

    @Override
    public String shape(String systemPrompt, AgentTask task) {
        if (config.knowledgeBaseId() == null || config.knowledgeBaseId().isBlank()) {
            return systemPrompt;
        }
        if (!config.enabledTools().contains("search_documents")) {
            return systemPrompt;
        }
        return systemPrompt + "\n\nKNOWLEDGE BASE INSTRUCTIONS:\n"
                + "You have access to a knowledge base. "
                + "The ONLY tool available for searching it is: search_documents\n"
                + "You MUST call it using EXACTLY this format before answering:\n"
                + "{\"tool_id\":\"search_documents\",\"arguments\":{\"query\":\"<your search query>\"}}\n"
                + "Do NOT use 'search', 'web_search', 'rag_search' or any other tool name. "
                + "Only 'search_documents' exists. "
                + "Base your final answer on the retrieved passages.";
    }
}
