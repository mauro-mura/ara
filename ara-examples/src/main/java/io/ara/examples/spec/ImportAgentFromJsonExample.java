package io.ara.examples.spec;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.spec.AgentSpec;
import io.ara.examples.support.DemoTools;
import io.ara.examples.support.Tools;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.spec.AgentSpecCheck;
import io.ara.runtime.spec.AgentSpecFormats;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Imports a fully described agent from a JSON file shipped with the application
 * ({@code resources/agents/analyst.json}) and runs it.
 *
 * <p>The document sets a fallback model, per-model prices and a spend cap, a typed strategy,
 * tools, limits and memory: everything {@code AgentConfig} can say, none of it Java. What the
 * document only <em>names</em> (the two models, the {@code echo} tool) is registered here,
 * which is why the file can be shared and reviewed without any secret in it. Offline: a
 * {@link ScriptedLlmClient} plays the model.
 */
public class ImportAgentFromJsonExample {

    public static void main(String[] args) throws IOException {
        // 1. Import. The caller owns the stream; a wrong document fails with the field's path.
        AgentSpec spec;
        try (InputStream in = ImportAgentFromJsonExample.class.getResourceAsStream("/agents/analyst.json")) {
            spec = AgentSpecFormats.defaults().read("json", in);
        }

        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("primary-model", ScriptedLlmClient.script()
                        .thenToolCall("echo", "{\"text\":\"Q3 revenue up 12%\"}")
                        .thenFinalAnswer("Revenue grew 12% in Q3.")
                        .build())
                .llmClient("backup-model", ScriptedLlmClient.script().thenFinalAnswer("unused").build())
                .toolRegistry(Tools.registry(DemoTools.echo()))
                .build()) {

            // 2. Check that every name in the document exists on this runtime.
            List<String> problems = AgentSpecCheck.problems(spec, runtime);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("document refers to unknown names: " + problems);
            }

            // 3. What came in.
            System.out.println("imported: " + spec.config().name() + " (" + spec.config().agentType() + ")");
            System.out.println("  models: " + spec.config().llmProvider() + " -> "
                    + spec.config().llm().fallbacks().get(0).transportId());
            System.out.println("  spend cap: " + spec.config().costBudget());
            System.out.println("  strategy: " + spec.config().execution().plannerStrategy()
                    + ", tools " + spec.config().execution().enabledTools());

            // 4. Run it like any agent.
            AgentResponse response = runtime.createAgent(spec.config()).execute(AgentTask.of("How was Q3?"));
            System.out.println("answer: " + response.content());
            System.out.println("cost: " + response.estimatedCost() + " in " + response.iterationsUsed() + " steps");
        }
    }
}
