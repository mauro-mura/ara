package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.ToolCallEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blank-text stream + completion sink: when the client reports its terminal completion
 * through the sink (native tool-call streaming — GLM-style tool-call deltas with no text
 * tokens), {@code streamAndCollect} must reuse it instead of re-issuing the whole call in
 * blocking mode. Only a stream that is blank AND leaves the sink empty may fall back.
 */
class StreamAndCollectCompletionSinkTest {

    private static final List<ToolCallEntry> TOOL_CALL = List.of(
            new ToolCallEntry("delegate_task", "{\"agent_id\":\"NL2SQL\",\"task\":\"x\"}", "call-1"));

    /** Streams no text at all; records whether the blocking fallback complete() is reached. */
    private static final class ToolCallOnlyStreamClient implements LlmClient {
        final LlmCompletion streamedCompletion;
        final AtomicInteger completeCalls = new AtomicInteger();

        ToolCallOnlyStreamClient(LlmCompletion streamedCompletion) {
            this.streamedCompletion = streamedCompletion;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            completeCalls.incrementAndGet();
            return new LlmCompletion("blocking fallback reached", 0, 0, "stop", null);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                boolean started;
                @Override public void request(long n) {
                    if (started) return;
                    started = true;
                    if (context != null && context.hasCompletionSink()) {
                        context.completionSink().accept(streamedCompletion);
                    }
                    subscriber.onComplete();   // zero tokens: blank text
                }
                @Override public void cancel() { }
            });
        }

        @Override public String providerId() { return "tool-call-only-stream-stub"; }
    }

    private record Run(LlmCompletion result, LlmClient client) {}

    private static Run run(LlmCompletion streamedCompletion) throws InterruptedException {
        ToolCallOnlyStreamClient llm = new ToolCallOnlyStreamClient(streamedCompletion);
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().modelId("stub").streamingEnabled(true).build())
                .executionTimeout(Duration.ofSeconds(5))
                .build();
        AgentTask task = AgentTask.ofStreaming("go", token -> { });
        LlmCallContext ctx = LlmCallContext.of(config, task);
        Instant deadline = Instant.now().plus(config.executionTimeout());
        LlmCompletion result = ReactExecutionSupport.streamAndCollect(
                llm, List.of(new LlmMessage("user", "go")), ctx, task, deadline, config);
        return new Run(result, llm);
    }

    @Test
    void blankStreamWithCapturedToolCallCompletion_reusesIt_withoutBlockingRetry() throws InterruptedException {
        LlmCompletion streamed = new LlmCompletion("", 100, 200, "tool_calls", null, "call-1", TOOL_CALL);
        Run run = run(streamed);

        assertSame(streamed, run.result(),
                "the streamed completion (real usage, tool calls) must be returned as-is");
        assertEquals(0, ((ToolCallOnlyStreamClient) run.client()).completeCalls.get(),
                "no duplicate blocking generation may be issued");
    }

    @Test
    void blankStreamWithEmptySink_fallsBackToBlocking_asBefore() throws InterruptedException {
        LlmCompletion noToolCall = new LlmCompletion("", 100, 200, "stop", null);
        Run run = run(noToolCall);

        assertEquals("blocking fallback reached", run.result().text());
        assertEquals(1, ((ToolCallOnlyStreamClient) run.client()).completeCalls.get(),
                "without a captured tool call the blocking retry must run, as before");
    }

    @Test
    void blankStreamWithCapturedTextOnlyCompletion_stillFallsBack() throws InterruptedException {
        // A text-less sink capture without tool calls gives the caller nothing it could not
        // get from a blocking retry with metadata — keep the old path.
        LlmCompletion textlessNoCall = new LlmCompletion("", 0, 0, "stop", null);
        Run run = run(textlessNoCall);

        assertEquals("blocking fallback reached", run.result().text());
    }

    @Test
    void sinkIsDetachedFromTheCallersContextObject() throws InterruptedException {
        // streamAndCollect attaches the sink through withCompletionSink (a copy); the
        // context object the caller passed must stay sink-less.
        ToolCallOnlyStreamClient llm = new ToolCallOnlyStreamClient(
                new LlmCompletion("", 0, 0, "tool_calls", null, "c1", TOOL_CALL));
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().modelId("stub").streamingEnabled(true).build())
                .executionTimeout(Duration.ofSeconds(5))
                .build();
        AgentTask task = AgentTask.ofStreaming("go", token -> { });
        LlmCallContext ctx = LlmCallContext.of(config, task);

        AtomicBoolean originalHasSink = new AtomicBoolean(ctx.hasCompletionSink());
        CountDownLatch done = new CountDownLatch(1);
        ReactExecutionSupport.streamAndCollect(
                llm, List.of(new LlmMessage("user", "go")), ctx, task,
                Instant.now().plusSeconds(5), config);
        done.countDown();

        assertTrue(originalHasSink.get() == ctx.hasCompletionSink(),
                "the caller's LlmCallContext must not be mutated with the internal sink");
    }
}
