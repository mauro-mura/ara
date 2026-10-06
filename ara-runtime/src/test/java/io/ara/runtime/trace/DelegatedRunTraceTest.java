package io.ara.runtime.trace;

import io.ara.core.agent.AgentConfig;
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
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run that delegates is one trace: the delegates' spans land in the caller's run, each
 * delegate's root hangs under the caller's root, and the same agent working twice does not
 * overwrite its own spans.
 */
class DelegatedRunTraceTest {

    private static final String DELEGATE = "{\"agent_id\":\"worker\",\"task\":\"summarise part %d\"}";

    @Test
    void twoDelegationsToOneWorker_areOneRun_withNoSpanLost() {
        TraceStore traces = TraceStore.inMemory();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", DELEGATE.formatted(1))
                        .thenToolCall("delegate_task", DELEGATE.formatted(2))
                        .thenFinalAnswer("both parts summarised")
                        .build())
                .llmClient("worker-model", ScriptedLlmClient.script()
                        .thenFinalAnswer("part 1 summary")
                        .thenFinalAnswer("part 2 summary")
                        .build())
                .traceEmission(traces, BlobStore.inMemory())
                .build();
        try {
            runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("worker")).agentType("summariser")
                    .primaryLlm(LlmProfile.of("worker-model")).plannerStrategy("react").build());
            AraAgent coordinator = runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("coordinator"))
                    .agentType("coordinator").primaryLlm(LlmProfile.of("coordinator-model"))
                    .plannerStrategy("react").enabledTools(List.of("delegate_task")).build());

            AgentResponse response = coordinator.execute(AgentTask.of("summarise the report"));
            assertTrue(response.isSuccess(), response.failureReason());

            // No correlation id, no session: the run is named after the coordinator's task.
            List<TraceSpan> run = traces.findByRunId(response.taskId());
            String coordinatorRoot = TraceProjection.rootSpanIdOf("coordinator", response.taskId());

            List<TraceSpan> parentless = run.stream().filter(s -> s.parentSpanId() == null).toList();
            assertEquals(1, parentless.size(), "exactly one root per run: " + parentless);
            assertEquals(coordinatorRoot, parentless.get(0).spanId());

            List<TraceSpan> workerRoots = run.stream()
                    .filter(s -> s.agentId().equals("worker") && s.spanId().endsWith("#run")).toList();
            assertEquals(2, workerRoots.size(), "both delegations are in the coordinator's run");
            assertTrue(workerRoots.stream().allMatch(s -> coordinatorRoot.equals(s.parentSpanId())),
                    "each delegate's root hangs under the coordinator's root");

            Set<String> ids = new HashSet<>();
            run.forEach(s -> ids.add(s.spanId()));
            assertEquals(run.size(), ids.size(), "span ids are unique within the run: nothing was overwritten");
        } finally {
            runtime.stop();
        }
    }
}
