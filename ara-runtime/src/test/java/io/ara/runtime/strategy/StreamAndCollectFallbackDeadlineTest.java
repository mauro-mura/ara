package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P0 row 4, §4 U4 — regression test.
 *
 * <p>Before U4, {@code ReactExecutionSupport.streamAndCollect}'s blank-text fallback called
 * {@code llm.complete(...)} raw — no deadline, no retry — even though the streaming call it
 * just gave up on was itself correctly bounded by {@code deadline}. A client whose {@code
 * stream()} legitimately emits blank text (see the method's own javadoc: the underlying
 * client used the blocking {@code complete()} fallback but only forwarded {@code .text()},
 * dropping a native tool-call response) but whose {@code complete()} then hangs would block
 * the task forever on the very call meant to recover from that.
 */
class StreamAndCollectFallbackDeadlineTest {

    /** Streams a single blank token (triggering the fallback), then hangs on the fallback {@code complete()} call. */
    private static final class BlankStreamThenHangOnFallbackLlmClient implements LlmClient {
        final CountDownLatch interruptedSignal = new CountDownLatch(1);

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
            }
            return new LlmCompletion("unreachable", 0, 0, "stop", null);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    subscriber.onNext("");   // blank — streamAndCollect must fall back to complete()
                    subscriber.onComplete();
                }
                @Override public void cancel() { }
            });
        }

        @Override public String providerId() { return "blank-then-hang-stub"; }
    }

    @Test
    void blankTextFallback_isBoundedByTheSameDeadline_whenCompleteHangs() throws InterruptedException {
        BlankStreamThenHangOnFallbackLlmClient llm = new BlankStreamThenHangOnFallbackLlmClient();
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().modelId("stub").streamingEnabled(true).build())
                .executionTimeout(Duration.ofSeconds(2))
                .build();
        List<String> streamed = new ArrayList<>();
        AgentTask task = AgentTask.ofStreaming("hi", streamed::add);
        LlmCallContext ctx = LlmCallContext.of(config, task);
        Instant deadline = Instant.now().plus(config.executionTimeout());

        long start = System.nanoTime();
        assertThrows(ExecutionTimeoutException.class, () -> ReactExecutionSupport.streamAndCollect(
                llm, List.of(new LlmMessage("user", "hi")), ctx, task, deadline, config));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(llm.interruptedSignal.await(5, TimeUnit.SECONDS),
                "deadline watchdog never interrupted the hung fallback complete() call");
        assertTrue(elapsedMs < 10_000,
                "a 30s hang should have been cut off in ~2s, took " + elapsedMs + "ms");
    }
}
