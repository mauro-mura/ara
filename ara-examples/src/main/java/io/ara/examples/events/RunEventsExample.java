package io.ara.examples.events;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentEvent;
import io.ara.core.agent.AgentTask;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.examples.support.DemoTools;
import io.ara.examples.support.Tools;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching an agent work: a listener on the task receives every step as it happens, including
 * the steps of the agent it delegates to.
 *
 * <p>The listener is what a chat page would use to show "thinking... calling a tool... got a
 * result". Here it prints, indenting each event by how deep in the delegation tree its run is:
 * every event names its run ({@code taskId}) and the run that delegated it ({@code
 * parentTaskId}), so the tree is rebuilt from a flat stream. Offline: scripted models.
 *
 * <p>The listener is called on the agent's own thread and from every delegated agent's thread,
 * so it must be quick and thread-safe: the map below is concurrent for that reason.
 */
public class RunEventsExample {

    public static void main(String[] args) {
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("boss-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"writer\",\"task\":\"draft one line\"}")
                        .thenFinalAnswer("The writer's line is ready.")
                        .build())
                .llmClient("writer-model", ScriptedLlmClient.script()
                        .thenToolCall("echo", "{\"text\":\"Hello\"}")
                        .thenFinalAnswer("Hello, world.")
                        .build())
                .toolRegistry(Tools.registry(DemoTools.echo()))
                .build()) {

            runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("writer")).agentType("writer")
                    .primaryLlm(LlmProfile.of("writer-model")).enabledTools(List.of("echo")).build());
            var boss = runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("boss")).agentType("boss")
                    .primaryLlm(LlmProfile.of("boss-model")).enabledTools(List.of("delegate_task")).build());

            Map<String, Integer> depthOfRun = new ConcurrentHashMap<>();
            boss.execute(AgentTask.of("Get me a line of text").withEventListener(event -> print(event, depthOfRun)));
        }
    }

    private static void print(AgentEvent event, Map<String, Integer> depthOfRun) {
        // A delegated run starts after its parent's RunStarted, so the parent's depth is known.
        int depth = event.parentTaskId() == null ? 0 : depthOfRun.get(event.parentTaskId()) + 1;
        depthOfRun.putIfAbsent(event.taskId(), depth);
        String indent = "  ".repeat(depth);

        String what = switch (event) {
            case AgentEvent.RunStarted started -> "started";
            case AgentEvent.StepRecorded recorded -> recorded.step().type() + " "
                    + (recorded.step().toolId() != null ? recorded.step().toolId() + " " + recorded.step().arguments()
                                                        : recorded.step().content().replace("\n", " "));
            case AgentEvent.RunFinished finished -> (finished.success() ? "finished" : "FAILED: " + finished.failureReason())
                    + " (" + finished.iterationsUsed() + " iterations, " + finished.estimatedCost() + ")";
        };
        System.out.println(indent + "[" + event.agentId() + " #" + event.seq() + "] " + what);
    }
}
