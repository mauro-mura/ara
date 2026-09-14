package io.ara.examples.failover;

import io.ara.adapters.embedding.EmbeddingEndpointPool;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EmbeddingException;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.memory.InMemoryDocumentStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows what ordered failover buys you on both call paths of an ARA runtime:
 * the completion call ({@code FailoverLlmClient}, enabled by
 * {@link LlmSelectionPolicy#FAILOVER}) and the embedding call
 * ({@code EmbeddingEndpointPool}).
 *
 * <p>No API keys, no network: every provider behind the pools is a scripted
 * stand-in that throws a controlled failure, so the demo is deterministic.
 *
 * <h2>LLM — the same outage, four agents</h2>
 * <ul>
 *   <li><b>{@code resilient}</b> — {@code FAILOVER} with a primary that always
 *       503s and a healthy fallback: the task still succeeds, thanks to the
 *       fallback.</li>
 *   <li><b>{@code bare}</b> — the same failing primary with no fallback
 *       (the {@code PRIMARY_ONLY} default): the outage kills the task. The
 *       contrast shows failover is what makes the difference, not the provider.</li>
 *   <li><b>{@code blind}</b> — {@code FAILOVER} but the primary fails with a
 *       <em>non-failover</em> error (401, invalid API key): the task fails
 *       even though the fallback is perfectly healthy. A 401 would recur on
 *       every candidate in the pool, so switching models would change nothing
 *       but the log noise — failover is a selective mechanism, not a blind
 *       retry.</li>
 *   <li><b>{@code breaker}</b> — the same always-503 primary under
 *       {@code FAILOVER}, but one agent driven across six runs that all share
 *       the same session. Every pool member hides behind a passive circuit
 *       breaker ({@code CircuitBreakerLlmClient}): the first three runs each
 *       pay one primary timeout, then the circuit opens and the last three runs
 *       never touch the primary at all — the fallback answers straight away. A
 *       single trial call after the 30s cooldown would re-probe the primary and
 *       close the circuit on success (not waited for here, so the demo stays
 *       deterministic). The state is per-session, exactly like the wiring it
 *       lives on (ADR-039): a fresh ephemeral session rebuilds the pool and the
 *       breaker from scratch, so a long-lived outage is mitigated within a
 *       conversation that keeps its session alive.</li>
 * </ul>
 *
 * <p>The {@code resilient} and {@code breaker} agents show the two halves of
 * failover's value: ordered → the primary is <em>replaced</em> mid-call; circuit →
 * the primary is <em>avoided</em> on later calls, so an outage that outlasts one
 * request stops charging a connect/read timeout every time.</p>
 *
 * <h2>Embedding — the same 503, two pools</h2>
 * <ul>
 *   <li>a pool whose primary endpoint always 503s, feeding an
 *       {@link InMemoryDocumentStore}: indexing and search still work, and
 *       {@link EmbeddingEndpointPool#lastUsedEndpoint()} names the fallback
 *       that served every vector;</li>
 *   <li>a pool whose primary 401s: {@code embed} aborts without touching the
 *       healthy fallback, for the same reason the {@code blind} agent fails.</li>
 * </ul>
 */
public class FailoverExample {

    public static void main(String[] args) {
        System.out.println("=== ARA - LLM and embedding failover ===\n");

        runLlmFailover();
        runEmbeddingFailover();
    }

    // ── LLM: FAILOVER policy across three agents ─────────────────────────────

    private static void runLlmFailover() {
        System.out.println("── LLM failover ──\n");

        LlmClient outagePrimary = new FailingLlmClient("llm-primary-503",
                LlmException.serverError("llm-primary-503", "HTTP 503 - service unavailable", 503));
        LlmClient authPrimary   = new FailingLlmClient("llm-primary-401",
                LlmException.authenticationError("llm-primary-401", "HTTP 401 - invalid API key"));
        LlmClient fallback      = new ScriptedLlmClient("llm-fallback",
                "Sono il modello di riserva: il provider primario non era disponibile, "
                + "ma il task e' comunque stato portato a termine.");
        // A second always-503 primary for the breaker scenario: its own attempt counter
        // (and its own circuit breaker) must not share history with `resilient`/`bare`.
        FailingLlmClient breakerPrimary = new FailingLlmClient("llm-breaker-primary",
                LlmException.serverError("llm-breaker-primary", "HTTP 503 - service unavailable", 503));

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("llm-primary-503",    outagePrimary)
                .llmClient("llm-primary-401",    authPrimary)
                .llmClient("llm-fallback",       fallback)
                .llmClient("llm-breaker-primary", breakerPrimary)
                .build();
        runtime.start();

        AgentConfig resilientConfig = agent("resilient",
                "llm-primary-503", "llm-fallback", LlmSelectionPolicy.FAILOVER);
        AgentConfig bareConfig       = agent("bare",
                "llm-primary-503", null,            LlmSelectionPolicy.PRIMARY_ONLY);
        AgentConfig blindConfig      = agent("blind",
                "llm-primary-401", "llm-fallback",  LlmSelectionPolicy.FAILOVER);
        AgentConfig breakerConfig    = agent("breaker",
                "llm-breaker-primary", "llm-fallback", LlmSelectionPolicy.FAILOVER);

        AraAgent resilient = runtime.createAgent(resilientConfig);
        AraAgent bare      = runtime.createAgent(bareConfig);
        AraAgent blind     = runtime.createAgent(blindConfig);
        AraAgent breaker   = runtime.createAgent(breakerConfig);

        System.out.println("[scenario] PRIMARY 503 + healthy fallback, policy FAILOVER");
        report("resilient", resilient.execute(AgentTask.of("Che cos'e' ARA?")));

        System.out.println("[scenario] same PRIMARY 503, no fallback (PRIMARY_ONLY)");
        report("bare",      bare.execute(AgentTask.of("Che cos'e' ARA?")));

        System.out.println("[scenario] PRIMARY 401 + healthy fallback, policy FAILOVER");
        report("blind",     blind.execute(AgentTask.of("Che cos'e' ARA?")));

        System.out.println("[scenario] PRIMARY 503 + healthy fallback, FAILOVER, agent kept alive");
        for (int run = 1; run <= 6; run++) {
            // Fixed sessionId so all six runs share one session — and therefore one wiring,
            // one pool, one circuit breaker (ADR-039 pins the wiring to the session).
            AgentTask breakerTask = AgentTask.of("Che cos'e' ARA?")
                    .withSessionId(SessionId.of("breaker-demo"));
            int triedBefore = breakerPrimary.attempts();
            AgentResponse outcome = breaker.execute(breakerTask);
            boolean primaryTried = breakerPrimary.attempts() > triedBefore;
            String verdict = primaryTried
                    ? (breakerPrimary.attempts() >= 3
                            ? "primary failed — third strike, circuit opens"
                            : "primary failed, fallback answered")
                    : "circuit OPEN — primary skipped, fallback answered";
            System.out.println("  run #" + run + " -> " + (outcome.isSuccess() ? "SUCCESS" : "FAILED")
                    + " (" + verdict + ")");
        }
        int hits = breakerPrimary.attempts();
        System.out.println("  breaker    : primary hit " + hits + " time(s), then skipped on the last "
                + (6 - hits) + " run(s) — a dead endpoint is never paid a timeout per request.");
        System.out.println("                a single trial after the 30s cooldown would re-probe it;"
                + " success closes the circuit. (Not waited for here — the demo must stay deterministic.)");

        runtime.stop();
        System.out.println();
    }

    private static AgentConfig agent(String name, String primaryId, String fallbackId,
                                     LlmSelectionPolicy policy) {
        AgentConfig.Builder b = AgentConfig.defaults()
                .agentId(AgentId.of(name))
                .agentType("failover-demo")
                .systemPrompt("Sei un assistente utile. Rispondi in modo diretto.")
                .primaryLlm(LlmProfile.of(primaryId))
                .llmSelectionPolicy(policy)
                .enabledTools(List.of())
                .maxIterations(1);
        if (fallbackId != null) b.fallbackLlm(LlmProfile.of(fallbackId));
        return b.build();
    }

    private static void report(String label, AgentResponse response) {
        System.out.println("  " + label + ": " + (response.isSuccess() ? "SUCCESS" : "FAILED"));
        System.out.println("    answer     : " + response.content());
        if (!response.isSuccess()) System.out.println("    failure    : " + response.failureReason());
    }

    // ── Embedding: EmbeddingEndpointPool across two failure modes ────────────

    private static void runEmbeddingFailover() {
        System.out.println("── Embedding failover ──\n");

        EmbeddingClient outagePrimary = new FailingEmbeddingClient("emb-primary-503",
                EmbeddingException.serverError("emb-primary-503", "HTTP 503 - service unavailable", 503));
        EmbeddingClient authPrimary   = new FailingEmbeddingClient("emb-primary-401",
                EmbeddingException.authenticationError("emb-primary-401", "HTTP 401 - invalid API key"));
        EmbeddingClient fallback      = new BagOfWordsEmbeddingClient("emb-fallback");

        EmbeddingEndpointPool failoverPool = new EmbeddingEndpointPool(List.of(outagePrimary, fallback));
        InMemoryDocumentStore kb = new InMemoryDocumentStore("failover-kb", failoverPool);
        kb.ensureCollection();
        kb.indexDocument("rag-notes", "failover notes",
                "Il failover su embedding prova prima il primo endpoint e, se cade con un "
                + "errore di rete o 5xx, passa al successivo in ordine di dichiarazione.");
        kb.indexDocument("llm-notes", "failover llm",
                "Il FailoverLlmClient fa lo stesso per le chiamate di completamento.");

        System.out.println("[scenario] embed endpoint 503 + healthy fallback -> indexing and search");
        System.out.println("  indexed   : " + kb.listDocuments().size() + " documents (each embed served by the fallback)");
        System.out.println("  top hit   : " + kb.search("come funziona il failover sugli embedding?", 1)
                .get(0).title());
        System.out.println("  last used : " + failoverPool.lastUsedEndpoint() + "\n");

        EmbeddingEndpointPool abortPool = new EmbeddingEndpointPool(List.of(authPrimary, fallback));
        System.out.println("[scenario] embed endpoint 401 + healthy fallback -> abort, fallback untouched");
        try {
            abortPool.embed("non dovrei mai arrivare qui");
        } catch (EmbeddingException e) {
            System.out.println("  embed throws: [" + e.category() + "] " + e.getMessage());
            System.out.println("  fallback untouched: " + abortPool.lastUsedEndpoint());
        }
        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo-only stand-ins — sostituire con client reali (ara-adapters) in
    // produzione: ogni scripted client e' un provider vero dietro la stessa
    // interfaccia, quindi il failover qui dimostrato resta identico.
    // ══════════════════════════════════════════════════════════════════════════

    /** LLM that always throws the same {@code LlmException} on every call. */
    static final class FailingLlmClient implements LlmClient {
        private final String        provider;
        private final LlmException  failure;
        private       int           attempts;

        FailingLlmClient(String provider, LlmException failure) {
            this.provider = provider;
            this.failure  = failure;
        }

        /** How many times the stand-in was actually called (see the circuit-breaker scenario). */
        int attempts() { return attempts; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) throws LlmException {
            System.out.printf("  [%s] call #%d -> throwing [%s]%n",
                    provider, ++attempts, failure.errorType());
            throw failure;
        }

        @Override
        public String providerId() { return provider; }
    }

    /** LLM that always answers with a canned FINAL_ANSWER. */
    static final class ScriptedLlmClient implements LlmClient {
        private final String provider;
        private final String answer;

        ScriptedLlmClient(String provider, String answer) {
            this.provider = provider;
            this.answer   = answer;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) {
            System.out.printf("  [%s] call -> answering%n", provider);
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: " + answer, 20, 15, "stop", null);
        }

        @Override
        public String providerId() { return provider; }
    }

    /** Embedding client that always throws the same {@code EmbeddingException}. */
    static final class FailingEmbeddingClient implements EmbeddingClient {
        private final String            provider;
        private final EmbeddingException failure;

        FailingEmbeddingClient(String provider, EmbeddingException failure) {
            this.provider = provider;
            this.failure  = failure;
        }

        @Override
        public List<Float> embed(String text) {
            System.out.printf("  [%s] embed -> throwing [%s]%n", provider, failure.category());
            throw failure;
        }

        @Override
        public int dimensions() { return 64; }

        @Override
        public String providerId() { return provider; }
    }

    /**
     * Deterministic off-line embedder: L2-normalised bag-of-words hash projected
     * onto a fixed-size vector. Good enough for cosine similarity in the demo —
     * NOT for production. Swapping it for a real {@link EmbeddingClient} (OpenAI,
     * Cohere, a local sentence-embedding model) changes nothing but the vectors,
     * because the failover mechanism sits in front of the interface, not behind it.
     */
    static final class BagOfWordsEmbeddingClient implements EmbeddingClient {
        private static final int DIM = 64;
        private final String provider;

        BagOfWordsEmbeddingClient(String provider) {
            this.provider = provider;
        }

        @Override
        public List<Float> embed(String text) {
            System.out.printf("  [%s] embed -> answering%n", provider);
            float[] v = new float[DIM];
            for (String token : text.toLowerCase().split("\\W+")) {
                if (token.isBlank()) continue;
                v[Math.floorMod(token.hashCode(), DIM)] += 1f;
            }
            float norm = 0f;
            for (float f : v) norm += f * f;
            norm = (float) Math.sqrt(norm);
            List<Float> out = new ArrayList<>(DIM);
            for (float f : v) out.add(norm > 0 ? f / norm : 0f);
            return out;
        }

        @Override
        public int dimensions() { return DIM; }

        @Override
        public String providerId() { return provider; }
    }
}