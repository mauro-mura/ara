package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentEvent;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a listener and a trace see when a {@link ParallelAgent} fans one task out to several
 * members. These tests <b>pin the behaviour as observed</b>; they do not endorse it.
 *
 * <p>Every member receives the same task, so they share its {@code taskId}. Consequences, all
 * asserted below: the run of one member is identified by ({@code agentId}, {@code taskId}), not by
 * {@code taskId} alone (events of the members interleave under one task id, each with its own
 * contiguous {@code seq}); the members' events carry no {@code parentTaskId}; and a trace has one
 * parentless root <em>per member</em> in the same run. Nothing is lost (span ids include the agent
 * id), but the run is a forest, not a tree, and the fan-out itself is not a node in it.
 *
 * <p>Giving the fan-out a node needs a decision about who its parent is: pipeline steps run with
 * the pipeline's own task, and workflow agent nodes with a fresh task that has no listener, so the
 * same question exists in three places. It is recorded in the events analysis, not decided here.
 * When it is, the assertions marked <em>current behaviour</em> are the ones that change.
 */
class ParallelAgentRunEventsTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static AgentConfig member(String id) {
        return AgentConfig.defaults().agentId(AgentId.of(id)).agentType("t")
                .primaryLlm(LlmProfile.of(id + "-model")).plannerStrategy("react").build();
    }

    @Test
    void membersOfAFanOut_eachHaveTheirOwnWellFormedEvents() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("left-model", ScriptedLlmClient.script().thenFinalAnswer("L").build())
                .llmClient("right-model", ScriptedLlmClient.script().thenFinalAnswer("R").build())
                .build()) {
            AraAgent left = runtime.createAgent(member("left"));
            AraAgent right = runtime.createAgent(member("right"));
            ParallelAgent fanout = new ParallelAgent(AgentId.of("fanout"), List.of(left, right), executor,
                    AgentChain.MergeStrategy.joining(","));
            AgentTask task = AgentTask.of("go").withEventListener(events::add);

            AgentResponse response = fanout.execute(task);

            assertTrue(response.isSuccess(), response.failureReason());
            for (String agent : List.of("left", "right")) {
                List<AgentEvent> own = events.stream().filter(e -> e.agentId().equals(agent)).toList();
                assertEquals(AgentEvent.RunStarted.class, own.get(0).getClass(), agent);
                assertEquals(AgentEvent.RunFinished.class, own.get(own.size() - 1).getClass(), agent);
                for (int i = 0; i < own.size(); i++) {
                    assertEquals(i, own.get(i).seq(), agent + ": seq is contiguous per agent within the task");
                }
            }
            // current behaviour: one task id for both members, and no parent link.
            assertEquals(1, events.stream().map(AgentEvent::taskId).distinct().count(),
                    "both members run under the fan-out's task id");
            assertTrue(events.stream().allMatch(e -> e.parentTaskId() == null), "no parent is recorded");
        }
    }

    @Test
    void membersOfAFanOut_traceIntoOneRun_withoutLosingASpan() {
        TraceStore traces = TraceStore.inMemory();
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("left-model", ScriptedLlmClient.script().thenFinalAnswer("L").build())
                .llmClient("right-model", ScriptedLlmClient.script().thenFinalAnswer("R").build())
                .traceEmission(traces, BlobStore.inMemory())
                .build()) {
            AraAgent left = runtime.createAgent(member("left"));
            AraAgent right = runtime.createAgent(member("right"));
            ParallelAgent fanout = new ParallelAgent(AgentId.of("fanout"), List.of(left, right), executor,
                    AgentChain.MergeStrategy.joining(","));
            AgentTask task = AgentTask.of("go");

            fanout.execute(task);

            List<TraceSpan> run = traces.findByRunId(task.taskId());
            List<TraceSpan> roots = run.stream().filter(s -> s.parentSpanId() == null).toList();
            // current behaviour: one parentless root per member, not one per run.
            assertEquals(2, roots.size(), "a root per member: " + roots);
            assertEquals(run.size(), run.stream().map(TraceSpan::spanId).distinct().count(), "no span id is reused");
            assertTrue(run.stream().anyMatch(s -> s.agentId().equals("left")));
            assertTrue(run.stream().anyMatch(s -> s.agentId().equals("right")));
        }
    }
}
