package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.AsyncSupport;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A coordinator that starts a sub-task in the background and carries on, through a real
 * runtime: the result arrives by itself, a final answer waits for open tasks, and nothing a run
 * started outlives it. The coordinator's model is scripted and records what it was shown; the
 * worker's model is held shut until the test lets it go.
 */
class AraRuntimeBackgroundDelegationTest {

    private static final String START = "{\"agent_id\":\"worker\",\"task\":\"find the sources\","
            + "\"wait\":false,\"label\":\"sources\"}";

    /** A model that blocks inside its call until released, and notes if it was interrupted. */
    private static final class GatedModel implements LlmClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: worker result", 5, 5, "stop", null);
        }

        @Override public String providerId() { return "gated-worker-model"; }
    }

    /** Wraps a model, keeps every message list it was called with, and runs a hook before each call. */
    private static final class RecordingModel implements LlmClient {
        final LlmClient inner;
        final List<List<LlmMessage>> calls = new CopyOnWriteArrayList<>();
        final IntConsumer beforeCall;

        RecordingModel(LlmClient inner, IntConsumer beforeCall) {
            this.inner = inner;
            this.beforeCall = beforeCall;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.add(List.copyOf(messages));
            beforeCall.accept(calls.size());
            return inner.complete(messages, context);
        }

        @Override public String providerId() { return "recording-coordinator-model"; }

        boolean sawInCall(int call, String text) {
            return calls.get(call - 1).stream().anyMatch(message -> message.content() != null && message.content().contains(text));
        }
    }

    private static AraRuntime runtimeWith(LlmClient coordinatorModel, GatedModel workerModel) {
        return AraRuntime.builder()
                .llmClient("coordinator-model", coordinatorModel)
                .llmClient("worker-model", workerModel)
                .llmClient("echo-model", ScriptedLlmClient.script().thenFinalAnswer("echo").build())
                .build();
    }

    private static void createAgents(AraRuntime runtime, Duration coordinatorTimeout) {
        runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model")).plannerStrategy("react").maxIterations(3).build());
        runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("echo")).agentType("t")
                .primaryLlm(LlmProfile.of("echo-model")).plannerStrategy("react").maxIterations(3).build());
        runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model")).plannerStrategy("react")
                .enabledTools(List.of("delegate_task")).asyncDelegation(AsyncDelegation.defaults())
                .maxIterations(6).executionTimeout(coordinatorTimeout).build());
    }

    private static AraAgent coordinator(AraRuntime runtime) {
        return runtime.registry().findById(AgentId.of("coordinator")).orElseThrow();
    }

    /** Waits until nothing the runtime started is still running. */
    private static void awaitIdle(AraRuntime runtime) throws InterruptedException {
        assertTrue(runtime.awaitQuiescence(Duration.ofSeconds(5)), "something is still running");
    }

    @Test
    void aFinishedTask_isDeliveredToTheModelByItself_atTheStartOfTheNextTurn() throws Exception {
        GatedModel worker = new GatedModel();
        AraRuntime[] runtimeHolder = new AraRuntime[1];
        RecordingModel model = new RecordingModel(ScriptedLlmClient.script()
                .thenToolCall("delegate_task", START)                                         // turn 1: start
                .thenToolCall("delegate_task", "{\"agent_id\":\"echo\",\"task\":\"own work\"}") // turn 2: own work
                .thenFinalAnswer("all done").build(), call -> {
            if (call == 2) {   // the worker finishes while the coordinator is busy
                await(worker.entered);
                worker.release.countDown();
                awaitIdleQuietly(runtimeHolder[0]);
            }
        });
        AraRuntime runtime = runtimeWith(model, worker);
        runtimeHolder[0] = runtime;
        createAgents(runtime, Duration.ofSeconds(30));

        AgentResponse response = coordinator(runtime).execute(AgentTask.of("research and answer"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals(3, model.calls.size());
        assertFalse(model.sawInCall(2, "worker result"), "nothing had finished yet at turn 2");
        assertTrue(model.sawInCall(3, "[automated — background task result, not a message from the user]"));
        assertTrue(model.sawInCall(3, "\"sources\" (worker): completed"));
        assertTrue(model.sawInCall(3, "worker result"));
        awaitIdle(runtime);
        runtime.stop();
    }

    @Test
    void aFinalAnswerGivenWhileATaskRuns_waitsForIt_andLetsTheModelAnswerAgainWithTheResult() throws Exception {
        GatedModel worker = new GatedModel();
        RecordingModel model = new RecordingModel(ScriptedLlmClient.script()
                .thenToolCall("delegate_task", START)
                .thenFinalAnswer("premature answer")
                .thenFinalAnswer("final answer using the sources").build(), call -> {
            if (call == 2) {   // let the worker go a moment after the model has answered
                Thread.ofVirtual().start(() -> {
                    sleep(300);
                    worker.release.countDown();
                });
            }
        });
        AraRuntime runtime = runtimeWith(model, worker);
        createAgents(runtime, Duration.ofSeconds(30));

        AgentResponse response = coordinator(runtime).execute(AgentTask.of("research and answer"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals("final answer using the sources", response.content().trim());
        assertEquals(3, model.calls.size(), "the premature answer must have bought one more turn");
        assertTrue(model.sawInCall(3, "worker result"));
        assertTrue(model.sawInCall(3, "[automated] Your background tasks have finished"));
        awaitIdle(runtime);
        runtime.stop();
    }

    @Test
    void aTaskThatDoesNotFinishBeforeTheDeadline_doesNotCostTheModelItsAnswer_andIsStopped() throws Exception {
        GatedModel worker = new GatedModel();   // never released
        RecordingModel model = new RecordingModel(ScriptedLlmClient.script()
                .thenToolCall("delegate_task", START)
                .thenFinalAnswer("the answer already in hand").build(), call -> { });
        AraRuntime runtime = runtimeWith(model, worker);
        createAgents(runtime, Duration.ofSeconds(1));

        AgentResponse response = coordinator(runtime).execute(AgentTask.of("research and answer"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals("the answer already in hand", response.content().trim());
        assertEquals(2, model.calls.size(), "waiting brought nothing new, so no extra turn is spent");
        assertTrue(waitUntil(worker.interrupted), "the task left running must be stopped with the run");
        awaitIdle(runtime);
        runtime.stop();
    }

    @Test
    void aRunThatFails_stopsItsBackgroundTasks() throws Exception {
        GatedModel worker = new GatedModel();   // never released
        AraRuntime[] runtimeHolder = new AraRuntime[1];
        RecordingModel model = new RecordingModel(ScriptedLlmClient.script()
                .thenToolCall("delegate_task", START).build(), call -> {
            if (call == 2) {
                await(worker.entered);
                throw new IllegalStateException("the provider went down");
            }
        });
        AraRuntime runtime = runtimeWith(model, worker);
        runtimeHolder[0] = runtime;
        createAgents(runtime, Duration.ofSeconds(30));

        AgentResponse response = coordinator(runtime).execute(AgentTask.of("research and answer"));

        assertFalse(response.isSuccess());
        assertTrue(waitUntil(worker.interrupted), "a failed run must not leave its task running");
        awaitIdle(runtime);
        runtime.stop();
    }

    @Test
    void aRecipientThatAcceptsOnlyWaitedDelegation_cannotBeStartedInTheBackground() throws Exception {
        GatedModel worker = new GatedModel();
        RecordingModel model = new RecordingModel(ScriptedLlmClient.script()
                .thenToolCall("delegate_task", START)
                .thenFinalAnswer("fine, no background").build(), call -> { });
        AraRuntime runtime = runtimeWith(model, worker);
        runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model")).plannerStrategy("react").maxIterations(3)
                .asyncSupport(AsyncSupport.FORBIDDEN).build());
        runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model")).plannerStrategy("react")
                .enabledTools(List.of("delegate_task")).asyncDelegation(AsyncDelegation.defaults())
                .maxIterations(4).build());

        AgentResponse response = coordinator(runtime).execute(AgentTask.of("research and answer"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertTrue(model.sawInCall(2, "accepts only waited delegation"));
        assertEquals(1, worker.entered.getCount(), "the worker must never have been started");
        runtime.stop();
    }

    @Test
    void anAgentThatDidNotOptIn_isUntouched_waitFalseIsJustIgnored() throws Exception {
        GatedModel worker = new GatedModel();
        worker.release.countDown();   // the waited call must be able to finish
        RecordingModel model = new RecordingModel(ScriptedLlmClient.script()
                .thenToolCall("delegate_task", START)
                .thenFinalAnswer("waited, as before").build(), call -> { });
        AraRuntime runtime = runtimeWith(model, worker);
        runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model")).plannerStrategy("react").maxIterations(3).build());
        runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model")).plannerStrategy("react")
                .enabledTools(List.of("delegate_task")).maxIterations(4).build());

        AgentResponse response = coordinator(runtime).execute(AgentTask.of("research and answer"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertTrue(model.sawInCall(2, "[worker] worker result"), "the call was waited and its answer observed directly");
        assertEquals(2, model.calls.size());
        runtime.stop();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out waiting for the worker");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitIdleQuietly(AraRuntime runtime) {
        try {
            assertTrue(runtime.awaitQuiescence(Duration.ofSeconds(5)), "the worker never finished");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitUntil(AtomicBoolean flag) throws InterruptedException {
        for (int i = 0; i < 100 && !flag.get(); i++) {
            Thread.sleep(50);
        }
        return flag.get();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
