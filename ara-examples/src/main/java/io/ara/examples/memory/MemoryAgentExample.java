package io.ara.examples.memory;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticStore;
import io.ara.examples.support.DemoEmbeddingClient;
import io.ara.runtime.AraRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows ARA's advanced working-memory management for a single agent (ADR-0078/ADR-0086):
 * a token-budget-governed window that, once full, does not just drop what no longer fits —
 * it <b>summarises</b> it (via a second, dedicated agent), <b>offloads</b> the original text
 * to an episodic store, and later <b>recalls</b> it on demand when a new question needs it.
 *
 * <p>None of this is bespoke to this example: it is the default {@code SlidingWindowMemoryManager}
 * that {@link AraRuntime.Builder#build()} now wires automatically whenever an
 * {@link AgentConfig} declares a token budget — see the three builder calls in step 2 below.
 * A config that never sets a budget keeps getting today's unlimited window unchanged.
 *
 * <h2>The scenario</h2>
 * One long conversation, one session, seven informational turns followed by a question:
 * <ol>
 *   <li>Turns 1-6 tell the assistant a mix of one important fact (the "Nimbus" project, turn 2)
 *       and several unrelated, forgettable details (a cat's name, a coffee count, a hobby...).</li>
 *   <li>The working-memory budget is small on purpose: by turn 3 or 4 the window is already
 *       full, and every following turn re-triggers eviction (working memory is rebuilt from
 *       the full conversation history on every turn — see {@code AgentInstance.seedWorkingMemory}
 *       — so the same old turns keep falling out of the live window again and again, not just
 *       once).</li>
 *   <li>The eviction policy is {@code SUMMARIZE}: the evicted middle of the window is collapsed
 *       into one short, <em>deliberately vague</em> summary by a second agent ({@code summarizer})
 *       — vague on purpose, so this demo cannot cheat by having the summary accidentally repeat
 *       the one keyword ("Nimbus") the final question needs.</li>
 *   <li>Before anything is summarised away, its original text is offloaded into a
 *       {@link SemanticStore} (the episodic-offload step). It is that store — not the
 *       summary — that makes the fact recoverable.</li>
 *   <li>Turn 7 asks a question about the project. By then turn 2 is long gone from the raw
 *       window (summarised away like everything else in the middle). The only way the agent
 *       still answers correctly is {@code recallRelevant} (the episodic-recall step): it
 *       embeds the question, searches
 *       the episodic store, and re-injects the original turn-2 text at the head of the window
 *       — this run prints the exact message list the LLM receives, so you can see it happen.</li>
 * </ol>
 *
 * <p>Both LLM calls (the assistant's and the summariser's) are scripted, so the demo is
 * deterministic and needs no API key. To use this for real:
 * <ul>
 *   <li>replace {@link DemoEmbeddingClient} with a real {@link EmbeddingClient}
 *       (e.g. an OpenAI {@code text-embedding-3-small} wrapper); the bag-of-words stand-in
 *       below is shared with {@code RagAgentExample}, which uses the identical technique;</li>
 *   <li>replace {@link DemoSemanticStore} with {@code QdrantSemanticStore} (same interface —
 *       nothing else in this example would need to change);</li>
 *   <li>replace {@code AssistantScript}/{@code SummarizerScript} with real {@code LlmClient}s
 *       from {@code ara-adapters}.</li>
 * </ul>
 *
 * @see io.ara.examples.rag.RagAgentExample — shares DemoEmbeddingClient and the vectorisation
 *     technique
 */
public class MemoryAgentExample {

    /** The one fact the demo cares about — deliberately never repeated in the vague summary. */
    private static final String KEY_FACT =
            "Il progetto a cui lavoro si chiama Nimbus: un sistema di orchestrazione di agenti "
            + "AI con memoria a lungo termine.";

    public static void main(String[] args) {
        System.out.println("=== ARA — advanced working-memory management (ADR-0086) ===\n");

        // ── 1. Episodic-offload collaborators — no external service required ────────
        EmbeddingClient embeddings = new DemoEmbeddingClient();
        DemoSemanticStore episodicStore = new DemoSemanticStore();

        // ── 2. Runtime — .embeddingClient/.semanticStore feed the default manager ──
        // Neither call is mandatory: without them, a budgeted agent would still get
        // eviction/summarisation, but no offload and no recall (ADR-0086: "ignored when
        // the other collaborator is not set").
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("assistant-llm",  new AssistantScript())
                .llmClient("summarizer-llm", new SummarizerScript())
                .embeddingClient(embeddings)
                .semanticStore(episodicStore)
                .build()) {

            runtime.start();

            // ── 3. The summariser agent — an ordinary AraAgent, not a special component ──
            AgentConfig summarizerConfig = AgentConfig.defaults()
                    .agentId(AgentId.of("summarizer"))
                    .agentType("summarizer")
                    .systemPrompt("Riassumi il testo ricevuto in una riga, in modo generico.")
                    .primaryLlm(LlmProfile.of("summarizer-llm"))
                    .plannerStrategy("react")
                    .enabledTools(List.of())
                    .maxIterations(1)
                    .build();
            runtime.createAgent(summarizerConfig);
            System.out.println("Agent created: summarizer (used only by the assistant's eviction)\n");

            // ── 4. The main agent — small budget, SUMMARIZE, summariser named by id ────
            AgentId assistantId = AgentId.of("assistant");
            AgentConfig assistantConfig = AgentConfig.defaults()
                    .agentId(assistantId)
                    .agentType("assistant")
                    .systemPrompt("Sei un assistente personale. Rispondi in una frase.")
                    .primaryLlm(LlmProfile.of("assistant-llm"))
                    .plannerStrategy("react")
                    .enabledTools(List.of())
                    .maxIterations(2)
                    .maxConversationTurns(20)          // replays the whole history on every turn
                    .workingMemoryTokenBudget(60)       // small on purpose: forces eviction early
                    .workingMemoryEviction("summarize") // instead of dropping, summarise
                    .contextSummarizerAgentId("summarizer")   // resolved from AgentRegistry at wiring-time
                    .build();
            AraAgent assistant = runtime.createAgent(assistantConfig);
            System.out.printf("Agent created: assistant (budget=%d tokens, eviction=summarize)%n%n",
                    assistantConfig.workingMemoryTokenBudget());

            SessionId session = SessionId.of("conversation-1");

            // ── 5. The conversation — one important fact, then mostly noise ────────────
            String[] turns = {
                    "Mi chiamo Marco, lavoro come ingegnere del software a Torino.",
                    KEY_FACT,
                    "Il mio gatto si chiama Pixel ed è nero.",
                    "Oggi ho bevuto tre caffè, forse troppi.",
                    "Il weekend scorso sono andato in montagna con degli amici.",
                    "Sto imparando a suonare la chitarra da qualche mese.",
            };
            for (int i = 0; i < turns.length; i++) {
                say(assistant, session, turns[i], "turn " + (i + 1));
            }

            // ── 6. The question — the correct answer is no longer in the raw window ────
            System.out.println("── Question that needs the fact now out of the raw window ──");
            AgentResponse answer = say(assistant, session,
                    "Come si chiama il progetto di cui ti ho parlato e cosa deve supportare?", "final question");

            System.out.println("\n=== Verification ===");
            boolean recalled = answer.content() != null && answer.content().contains("Nimbus");
            System.out.printf("%-13s : %s%n", "Answer cites 'Nimbus'", recalled);
            System.out.printf("%-13s : %d%n", "Offloaded entries",
                    episodicStore.countFor(assistantId.value()));
            System.out.println("(a high number is expected: the window is rebuilt from scratch on every "
                    + "turn from the full history, so the same old turns are re-evaluated and "
                    + "re-offloaded on every round — offload is best-effort, with no deduplication, "
                    + "in this increment of ADR-0086/ADR-0078)");
            System.out.println(recalled
                    ? "→ the episodic store recalled the fact that was no longer in the raw window."
                    : "→ something went wrong: try raising workingMemoryTokenBudget or the number of turns.");
        }
    }

    /** Runs one turn, printing what the assistant received and replied. */
    private static AgentResponse say(AraAgent agent, SessionId session,
                                      String userInput, String label) {
        System.out.printf("[%s] user: %s%n", label, userInput);
        AgentResponse response = agent.execute(AgentTask.of(userInput).withSessionId(session));
        System.out.printf("[%s] assistant: %s%n%n", label, response.content());
        return response;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo-only stand-ins — replace with real implementations in production
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * In-process {@link SemanticStore}: ranks by cosine similarity instead of {@code
     * RecordingSemanticStore}-style "return everything" fakes, so the recall in step 6 is a
     * genuine nearest-neighbour search, not a coincidence of insertion order. Scoped by
     * {@code agentId} exactly like the real {@code QdrantSemanticStore} (ADR-0060) — one map
     * per agent, never a global pool.
     */
    static final class DemoSemanticStore implements SemanticStore {
        record Entry(MemoryEntry entry, List<Float> vector) {}

        final Map<String, List<Entry>> byAgent = new HashMap<>();

        /** How many entries this agent has offloaded so far — for the verification output. */
        int countFor(String agentId) {
            return byAgent.getOrDefault(agentId, List.of()).size();
        }

        @Override
        public void upsert(String agentId, String role, String type, String content, List<Float> vector) {
            byAgent.computeIfAbsent(agentId, k -> new ArrayList<>())
                    .add(new Entry(MemoryEntry.of(role, content), vector));
        }

        @Override
        public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
            return byAgent.getOrDefault(agentId, List.of()).stream()
                    .sorted(Comparator.<Entry>comparingDouble(e -> cosine(e.vector(), queryVector)).reversed())
                    .limit(limit)
                    .map(Entry::entry)
                    .toList();
        }

        private static double cosine(List<Float> a, List<Float> b) {
            double dot = 0;
            for (int i = 0; i < a.size(); i++) dot += a.get(i) * b.get(i);
            return dot; // both vectors are already L2-normalised by DemoEmbeddingClient
        }
    }

    /**
     * Scripted assistant: acknowledges every informational turn with a short filler reply,
     * and on a question, answers "Nimbus" only if that word is literally present somewhere
     * in the messages it was sent — proving the answer comes from what memory actually
     * handed it, not from anything hidden inside this script.
     */
    static final class AssistantScript implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) {
            String input = messages.get(messages.size() - 1).content();
            String answer;
            if (input.contains("?")) {
                boolean hasFact = messages.stream().anyMatch(m -> m.content().contains("Nimbus"));
                answer = hasFact
                        ? "Il progetto si chiama Nimbus e deve supportare la memoria a lungo termine degli agenti."
                        : "Non ricordo un progetto con quel nome, mi dispiace.";
            } else {
                answer = "Capito, grazie per l'informazione.";
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: " + answer, 20, 15, "stop", null);
        }

        @Override
        public String providerId() { return "assistant-llm"; }
    }

    /**
     * Scripted summariser: always returns the same generic sentence, regardless of what it is
     * asked to summarise. Deliberately useless as a summary — the point of this demo is that
     * the summary is <em>not</em> where the recalled fact comes from, the episodic store is.
     */
    static final class SummarizerScript implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) {
            return new LlmCompletion(
                    "Action: FINAL_ANSWER\nAnswer: L'utente ha condiviso alcune informazioni personali.",
                    15, 10, "stop", null);
        }

        @Override
        public String providerId() { return "summarizer-llm"; }
    }
}
