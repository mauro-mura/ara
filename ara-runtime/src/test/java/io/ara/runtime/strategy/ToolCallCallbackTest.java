package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ToolCallEvent;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AgentTask} carries per-run hooks a UI can subscribe to; the gateway turns them into
 * server-sent-event frames. This pins what the runtime actually does with the tool-call hook:
 * when the agent calls a tool during a run, the subscriber is told, with the tool id and the
 * arguments, before the run ends.
 */
class ToolCallCallbackTest {

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success("echo", "ECHO"); }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return ids.contains("echo") ? List.of(ECHO) : List.of(); }
        @Override public Optional<AraTool> findById(String id) { return "echo".equals(id) ? Optional.of(ECHO) : Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return ECHO.execute(json); }
    };

    private static AgentResponse run(AgentTask task) {
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("echo", "{\"text\":\"hello\"}")
                        .thenFinalAnswer("done")
                        .build())
                .toolRegistry(REGISTRY)
                .build()) {
            return runtime.createAgent(AgentConfig.defaults().agentType("t").primaryLlm(LlmProfile.of("default"))
                    .plannerStrategy("react").enabledTools(List.of("echo")).build()).execute(task);
        }
    }

    @Test
    void aToolCallDuringARun_reachesTheTaskCallback() {
        List<ToolCallEvent> events = new CopyOnWriteArrayList<>();

        AgentResponse response = run(AgentTask.of("say hello").withToolCallCallback(events::add));

        assertTrue(response.isSuccess(), "the run itself must work: " + response.failureReason());
        assertEquals(1, events.size(), "one tool call was made, so the subscriber must hear of one");
        assertEquals("echo", events.get(0).toolId());
        assertTrue(events.get(0).argumentJson().contains("hello"), events.get(0).argumentJson());
    }

    @Test
    void withoutACallback_theRunIsUnaffected() {
        AgentResponse response = run(AgentTask.of("say hello"));

        assertTrue(response.isSuccess());
        assertEquals("done", response.content());
    }
}
