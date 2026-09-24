package io.ara.examples.basics;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.examples.support.DemoTools;
import io.ara.examples.support.LoggingInterceptor;
import io.ara.examples.support.Tools;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.util.List;

/**
 * End-to-end smoke test for the ARA agent runtime — the runnable companion to the
 * README quick start.
 *
 * <p>Wires the full stack (AraRuntime → AgentInstance → ReactStrategy → LlmClient → Tool)
 * with an in-memory {@link ScriptedLlmClient}, so the demo runs offline: no network, no
 * API key, no local model. The LLM side is scripted with the same stub the README uses,
 * so the first example a reader runs is the first snippet they read. (An earlier version
 * hand-rolled its own stub {@code LlmClient}; that duplicated what this class exists to
 * teach and diverged from the quick start, so it was discarded.)
 *
 * <p>Scripted scenario:
 * <ol>
 *   <li>LLM call 1 — the script asks for the {@code echo} tool.</li>
 *   <li>{@code echo} returns its observation, appended to working memory.</li>
 *   <li>LLM call 2 — the script emits its final answer, ending the ReAct loop.</li>
 * </ol>
 *
 * <p>The {@code echo} tool, the interceptor and the tool registry come from
 * {@code io.ara.examples.support} rather than being declared inline, so the example body
 * stays focused on the runtime wiring. The runtime is never started explicitly: the first
 * {@code createAgent} auto-starts it (see {@code AraRuntime#createAgent}).
 */
public class AraSimpleExample {

    public static void main(String[] args) {

        System.out.println("=== ARA Agent Runtime — Demo ===\n");

        // ── 1. Build runtime ──────────────────────────────────────────────────
        // ScriptedLlmClient.script() replays canned completions in order: first a tool
        // call, then a final answer. The tool registry and the interceptor are the only
        // other dependencies, and both are optional.
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("echo", "{\"text\":\"Hello ARA\"}")
                        .thenFinalAnswer("The echo tool responded: Hello ARA")
                        .build())
                .toolRegistry(Tools.registry(DemoTools.echo()))
                .interceptors(List.of(new LoggingInterceptor()))
                .build()) {

            // ── 2. Create agent ───────────────────────────────────────────────────
            AgentConfig config = AgentConfig.defaults()
                    .agentType("demo-agent")
                    .systemPrompt("You are a helpful demo agent.")
                    .plannerStrategy("react")
                    .enabledTools(List.of("echo"))
                    .maxIterations(5)
                    .build();

            AraAgent agent = runtime.createAgent(config);
            System.out.printf("Agent created  : %s%n", agent.agentId().value());
            System.out.printf("Initial state  : %s%n%n", agent.currentState());

            // ── 3. Execute a task ─────────────────────────────────────────────────
            AgentTask task = AgentTask.of("What does the echo tool say about 'Hello ARA'?");
            System.out.printf("Submitting task: %s%n%n", task.input());

            AgentResponse response = agent.execute(task);

            // ── 4. Print result ───────────────────────────────────────────────────
            System.out.println("\n=== Result ===");
            System.out.printf("Success        : %s%n", response.isSuccess());
            System.out.printf("Final state    : %s%n", response.finalState());
            System.out.printf("Content        : %s%n", response.content());
            System.out.printf("Iterations     : %d%n", response.iterationsUsed());
            System.out.printf("Tokens         : %d%n", response.totalTokens());
            System.out.printf("Estimated cost : %s %s%n",
                    response.estimatedCost().amount().toPlainString(), response.estimatedCost().currency());
            System.out.printf("Elapsed        : %dms%n", response.elapsedTime().toMillis());
            System.out.printf("Agent state    : %s (back to IDLE, ready for reuse)%n",
                    agent.currentState());

            // ── 5. Reuse the same agent for a second task ─────────────────────────
            // The script is exhausted, so the stub falls back to a canned final answer —
            // enough to show an agent instance taking a second task without being rebuilt.
            System.out.println("\n=== Second task (instance reuse) ===");
            AgentTask task2 = AgentTask.of("Give me a direct answer, no tools needed.");
            AgentResponse response2 = agent.execute(task2);
            System.out.printf("Success        : %s%n", response2.isSuccess());
            System.out.printf("Content        : %s%n", response2.content());

            // ── 6. Cleanup ─────────────────────────────────────────────────────────
            runtime.destroyAgent(agent);
            System.out.printf("%nRegistry count after destroy: %d%n", runtime.registry().count());
        }
    }
}
