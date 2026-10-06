package io.ara.examples.spec;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.spec.AgentSpec;
import io.ara.examples.support.DemoTools;
import io.ara.examples.support.Tools;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.spec.AgentSpecCheck;
import io.ara.runtime.spec.AgentSpecFormats;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * An agent defined in a file instead of Java: load it, check the names it refers to, run it,
 * and write it back out.
 *
 * <p>Runs offline (a {@link ScriptedLlmClient} stands in for the model). The document names the
 * model ({@code "main"}) and the tool ({@code "echo"}) and does not define them: those are
 * registered on the runtime, which is also why no API key can ever appear in the file.
 *
 * <p>The second document has two typos that {@code createAgent} would accept without a word —
 * a tool that does not exist (the agent would simply run without it) and a model that does not
 * exist (the runtime would quietly use its default client, i.e. another model). {@link
 * AgentSpecCheck} is what turns them into messages.
 */
public class AgentFromFileExample {

    private static final String DOCUMENT = """
            {
              "schemaVersion": 1,
              "agent": {
                "type": "demo-echo",
                "name": "Echo agent",
                "systemPrompt": "You repeat what the tool tells you."
              },
              "llm": { "primary": { "model": "main", "temperature": 0.0 } },
              "execution": { "strategy": "react", "tools": ["echo"], "maxIterations": 4 }
            }
            """;

    public static void main(String[] args) throws IOException {
        AgentSpecFormats formats = AgentSpecFormats.defaults();
        Path file = Files.createTempFile("echo-agent", ".json");
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("main", ScriptedLlmClient.script()
                        .thenToolCall("echo", "{\"text\":\"Hello from a file\"}")
                        .thenFinalAnswer("The echo tool said: Hello from a file")
                        .build())
                .toolRegistry(Tools.registry(DemoTools.echo()))
                .build()) {

            // 1. Load. The extension picks the format; a wrong document fails with the field path.
            Files.writeString(file, DOCUMENT);
            AgentSpec spec = formats.read(file);

            // 2. Check the names against this runtime. Nothing here is mandatory: it is the
            //    one place a misspelt name is reported instead of silently tolerated.
            List<String> problems = AgentSpecCheck.problems(spec, runtime);
            System.out.println("problems with the first document: " + problems);

            // 3. Run it like any other agent: a spec is a definition, its config is the same
            //    AgentConfig the builder would have produced.
            AraAgent agent = runtime.createAgent(spec.config());
            AgentResponse response = agent.execute(AgentTask.of("say hello"));
            System.out.println("answer: " + response.content());

            // 4. A document with two typos, and what the check says about it.
            String typos = DOCUMENT.replace("\"echo\"]", "\"ecoh\"]").replace("\"main\"", "\"mian\"");
            Files.writeString(file, typos);
            AgentSpecCheck.problems(formats.read(file), runtime).forEach(p -> System.out.println("  - " + p));

            // 5. Write it back. Keys come out in a fixed reading order, every field present, so
            //    two exports of one agent are byte-identical and diff cleanly in version control.
            formats.write(spec, file);
            System.out.println(Files.readString(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
