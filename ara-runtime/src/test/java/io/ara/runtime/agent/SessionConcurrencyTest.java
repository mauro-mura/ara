package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionBusyPolicy;
import io.ara.core.agent.SessionId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-session concurrency model (ADR-016):
 * <ul>
 *   <li>per-session termination does not poison the agent for other sessions;</li>
 *   <li>{@link SessionBusyPolicy#REJECT} vs {@link SessionBusyPolicy#ENQUEUE};</li>
 *   <li>async parallel execution of different sessions via {@link AraRuntime#submit}.</li>
 * </ul>
 */
class SessionConcurrencyTest {

    /** LLM stub whose first {@code complete} call blocks until released; later calls return immediately. */
    private static final class GatedClient implements LlmClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        final AtomicInteger  calls   = new AtomicInteger();

        GatedClient(CountDownLatch release) { this.release = release; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new LlmCompletion("interrupted", 1, 1, "stop", null);
                }
            }
            return new LlmCompletion("done", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "gated-stub"; }
    }

    private static AraRuntime runtimeWith(LlmClient client) {
        AraRuntime runtime = AraRuntime.builder().llmClient(client).build();
        runtime.start();
        return runtime;
    }

    private static AgentConfig config(SessionBusyPolicy policy) {
        return AgentConfig.defaults()
                .agentType("t")
                .systemPrompt("sys")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                .sessionBusyPolicy(policy)
                .maxIterations(3)
                .build();
    }

    private static AgentTask taskOn(String sessionId) {
        return AgentTask.of("hi").withSessionId(SessionId.of(sessionId));
    }

    @Test
    void terminateSession_doesNotPoisonOtherSessions() {
        CountDownLatch release = new CountDownLatch(0);   // never blocks
        AraRuntime runtime = runtimeWith(new GatedClient(release));
        AraAgent agent = runtime.createAgent(config(SessionBusyPolicy.REJECT));

        // Cancelling a session that isn't running must be harmless…
        ((SessionScoped) agent).terminate(SessionId.of("ghost"));

        // …and any session still executes normally afterwards.
        assertTrue(agent.execute(taskOn("s2")).isSuccess());
        assertTrue(agent.execute(taskOn("s1")).isSuccess());

        runtime.stop();
    }

    @Test
    void rejectPolicy_rejectsSecondConcurrentTaskOnSameSession() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GatedClient client = new GatedClient(release);
        AraRuntime runtime = runtimeWith(client);
        AraAgent agent = runtime.createAgent(config(SessionBusyPolicy.REJECT));

        Thread a = new Thread(() -> agent.execute(taskOn("s1")));
        a.start();
        assertTrue(client.entered.await(5, TimeUnit.SECONDS), "first task should be running");

        // Second task on the SAME session while the first is in-flight → rejected.
        AgentResponse second = agent.execute(taskOn("s1"));
        assertFalse(second.isSuccess());
        assertTrue(second.failureReason().contains("Session busy"));

        release.countDown();
        a.join(5_000);
        runtime.stop();
    }

    @Test
    void enqueuePolicy_queuesSecondTaskOnSameSession() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GatedClient client = new GatedClient(release);
        AraRuntime runtime = runtimeWith(client);
        AraAgent agent = runtime.createAgent(config(SessionBusyPolicy.ENQUEUE));

        Thread a = new Thread(() -> agent.execute(taskOn("s1")));
        a.start();
        assertTrue(client.entered.await(5, TimeUnit.SECONDS), "first task should be running");

        // Second task on the SAME session queues behind the first instead of being rejected.
        AtomicInteger secondOk = new AtomicInteger(-1);
        Thread b = new Thread(() -> secondOk.set(agent.execute(taskOn("s1")).isSuccess() ? 1 : 0));
        b.start();

        // b must still be blocked on the session lock while a holds it.
        Thread.sleep(200);
        assertTrue(b.isAlive(), "second task should be queued, not rejected");

        release.countDown();
        a.join(5_000);
        b.join(5_000);
        assertTrue(secondOk.get() == 1, "queued task should complete successfully");
        runtime.stop();
    }

    @Test
    void submit_runsDifferentSessionsInParallel() throws Exception {
        CountDownLatch release = new CountDownLatch(0);   // never blocks
        AraRuntime runtime = runtimeWith(new GatedClient(release));
        AraAgent agent = runtime.createAgent(config(SessionBusyPolicy.REJECT));

        var f1 = runtime.submit(agent, taskOn("s1"));
        var f2 = runtime.submit(agent, taskOn("s2"));

        assertTrue(f1.get().isSuccess());
        assertTrue(f2.get().isSuccess());
        runtime.stop();
    }
}
