package io.ara.core.agent;

/**
 * Immutable snapshot of a single conversational exchange — one user input and
 * the corresponding assistant output produced by the agent.
 *
 * <p>Stored in {@code ConversationHistory} (ara-runtime) and replayed into working
 * memory at the start of each subsequent task so the LLM can maintain continuity
 * across calls within the same session. Lives in {@code ara-core} (not alongside
 * {@code ConversationHistory}) so {@link SessionStore} can reference it without a
 * module-boundary violation.
 */
public record ConversationTurn(String userInput, String assistantOutput) {

    public ConversationTurn {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be blank");
        }
        if (assistantOutput == null) {
            assistantOutput = "";
        }
    }
}
