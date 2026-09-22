package io.ara.examples.basics;

import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.examples.support.DemoTools;
import io.ara.examples.support.Live;
import io.ara.examples.support.LoggingInterceptor;
import io.ara.examples.support.Tools;
import io.ara.runtime.AraRuntime;

import java.util.List;

/**
 * Live variant of {@link AraSimpleExample}: same structure, same {@code echo} tool,
 * same interceptor — but with a real LLM model via {@link OpenAiLlmClient}.
 *
 * <p>Configure {@link #BASE_URL}, {@link #API_KEY} and {@link #MODEL} before running.
 * Omit {@code BASE_URL} (set it to {@code null}) to target hosted OpenAI.
 */
public class AraSimpleExampleLive {

    private static final String BASE_URL = "http://127.0.0.1:1234/v1";  // local server for example LM Studio
    private static final String API_KEY  = Live.apiKeyRequired();
    private static final String MODEL    = "openai/gpt-oss-20b"; // local model

    public static void main(String[] args) {

        System.out.println("=== ARA Agent Runtime — Live Demo ===\n");

        // ── 1. The model client — a real endpoint behind the same LlmClient ─────
        LlmClient local = OpenAiLlmClient.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .modelName(MODEL)
                .build();

        // ── 2. Build runtime — same wiring as the stub sibling, real client ─────
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("local", local)
                .toolRegistry(Tools.registry(DemoTools.echo()))
                .interceptors(List.of(new LoggingInterceptor()))
                .build()) {

            runtime.start();

            // ── 3. Create agent config — echo enabled, react loop, like the sibling stub ──
            AgentConfig config = AgentConfig.defaults()
                    .agentType("demo-agent")
                    .systemPrompt("You are a helpful demo agent.")
                    .primaryLlm(LlmProfile.of("local"))
                    .plannerStrategy("react")
                    .enabledTools(List.of("echo"))
                    .maxIterations(5)
                    .build();

            // ── 4. Create agent ──────────────────────────────────────────────────
            AraAgent agent = runtime.createAgent(config);
            System.out.printf("Agent created  : %s%n", agent.agentId().value());
            System.out.printf("Initial state  : %s%n%n", agent.currentState());

            // ── 5. Create task ───────────────────────────────────────────────────
            AgentTask task = AgentTask.of("What does the echo tool say about 'Hello ARA'?");
            System.out.printf("Submitting task: %s%n%n", task.input());

            // ── 6. Execute task ──────────────────────────────────────────────────
            AgentResponse response = agent.execute(task);

            // ── 7. Print results ─────────────────────────────────────────────────
            System.out.println("\n=== Result ===");
            System.out.printf("Success        : %s%n", response.isSuccess());
            System.out.printf("Final state    : %s%n", response.finalState());
            System.out.printf("Content        : %s%n", response.content());
            System.out.printf("Iterations     : %d%n", response.iterationsUsed());
            System.out.printf("Tokens         : %d%n", response.totalTokens());
            System.out.printf("Estimated cost : %s %s%n",
                    response.estimatedCost().amount().toPlainString(), response.estimatedCost().currency());
            System.out.printf("Elapsed        : %dms%n", response.elapsedTime().toMillis());
            System.out.printf("Agent state    : %s (back to IDLE, ready for reuse)%n", agent.currentState());

            // ── 8. Cleanup — destroy the agent, registry back to empty ─────────────────
            runtime.destroyAgent(agent);
            System.out.printf("%nRegistry count after destroy: %d%n", runtime.registry().count());
        }
    }
}
