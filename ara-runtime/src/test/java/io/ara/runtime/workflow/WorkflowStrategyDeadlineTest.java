package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P7 row (WorkflowStrategy pool
 * close()), §4 U20 — regression test.
 *
 * <p>{@code WorkflowStrategy.execute()} used to run {@link Workflow#run} inside a {@code
 * try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())} block. Even
 * once {@link DataflowScheduler}'s own deadline (this same phase's other fix) made {@code
 * workflow.run(...)} itself return promptly on a hung node, the try-with-resources'
 * {@code close()} — the JDK 19+ default {@link AutoCloseable} behaviour for {@code
 * ExecutorService} — still waited for every submitted task to finish, including the one
 * just abandoned. A hung node turned a bounded workflow run back into an unbounded
 * {@code execute()} call, one step later.
 */
class WorkflowStrategyDeadlineTest {

    @Test
    void aHungNode_doesNotBlockExecuteOnThePoolsClose() throws Exception {
        CountDownLatch neverCounts = new CountDownLatch(1);
        Workflow workflow = Workflow.of()
                .node("hung", in -> {
                    try {
                        neverCounts.await(30, TimeUnit.SECONDS);   // simulates a node that never returns
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "unreachable";
                })
                .build();

        AgentConfig config = AgentConfig.defaults()
                .agentId(AgentId.of("hung-workflow"))
                .agentType("workflow")
                .executionTimeout(Duration.ofMillis(200))
                .build();
        AraAgent agent = WorkflowAgents.of(AgentId.of("hung-workflow"), config, workflow);

        long start = System.nanoTime();
        AgentResponse response = agent.execute(AgentTask.of("go"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(response.isSuccess());
        assertTrue(elapsedMs < 5_000,
                "execute() must not block on the pool's close() waiting for the abandoned node — took " + elapsedMs + "ms");
    }
}
