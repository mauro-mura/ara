package io.ara.adapters.llm;

import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks end to end that cancelling a subscription really stops tokens, through a real adapter
 * and a real HTTP stream rather than through a hand-driven handler.
 *
 * <p>This is the scenario the runtime depends on. {@code ReactExecutionSupport.streamAndCollect}
 * cancels the subscription when a streaming call blows its deadline, precisely so a task that
 * has already been reported as failed stops accumulating text and stops pushing tokens at an
 * SSE client. Every adapter used to leave {@code cancel()} empty — or cancel a future nothing
 * observed — so that protection did not exist, and only a test at this level can tell the
 * difference: the unit tests pass a handler the test itself drives, which cannot prove the
 * adapter wired its subscription to anything real.
 *
 * <p>Ollama is the adapter under test because its wire format is newline-delimited JSON, which
 * a stub can emit token by token without an SSE framing library. The cancellation logic is
 * shared by all three adapters via {@link TokenStreamPublisher}.
 */
class TokenStreamPublisherCancellationTest {

    private static final int    TOTAL_CHUNKS   = 20;
    private static final int    CANCEL_AFTER   = 3;
    private static final Duration BETWEEN_CHUNKS = Duration.ofMillis(30);

    /** One NDJSON chunk per token, then the terminating {@code done} line Ollama sends. */
    private static List<String> ndJsonChunks() {
        List<String> lines = new ArrayList<>(IntStream.range(0, TOTAL_CHUNKS)
                .mapToObj(i -> """
                        {"model":"llama3.2","created_at":"2024-01-01T00:00:00Z",
                         "message":{"role":"assistant","content":"t%d "},"done":false}"""
                        .formatted(i).replace("\n", ""))
                .toList());
        lines.add("""
                {"model":"llama3.2","created_at":"2024-01-01T00:00:00Z",
                 "message":{"role":"assistant","content":""},"done":true,
                 "done_reason":"stop","prompt_eval_count":1,"eval_count":1}"""
                .replace("\n", ""));
        return lines;
    }

    @Test
    void cancellingStopsTokenDeliveryAndSuppressesCompletion() throws Exception {
        try (StubLlmProvider provider =
                     StubLlmProvider.streamingNdJson(ndJsonChunks(), BETWEEN_CHUNKS)) {

            LlmClient client = OllamaLlmClient.builder()
                    .baseUrl(provider.baseUrl())
                    .modelName("llama3.2")
                    .timeout(Duration.ofSeconds(20))
                    .build();

            List<String> received = new ArrayList<>();
            CountDownLatch cancelled = new CountDownLatch(1);
            boolean[] terminated = {false};

            client.stream(List.of(new LlmMessage("user", "hi")),
                            new LlmCallContext.Builder().agentType("test").build())
                    .subscribe(new Flow.Subscriber<>() {
                        Flow.Subscription subscription;

                        @Override public void onSubscribe(Flow.Subscription s) {
                            subscription = s;
                            s.request(Long.MAX_VALUE);
                        }
                        @Override public void onNext(String token) {
                            received.add(token);
                            if (received.size() == CANCEL_AFTER) {
                                subscription.cancel();
                                cancelled.countDown();
                            }
                        }
                        @Override public void onError(Throwable t) { terminated[0] = true; }
                        @Override public void onComplete()         { terminated[0] = true; }
                    });

            assertTrue(cancelled.await(15, TimeUnit.SECONDS), "the stream never reached the cancel point");

            // Long enough for every remaining chunk to have been delivered had cancel not
            // taken effect — the stub is still sending them, they are simply discarded now.
            Thread.sleep(BETWEEN_CHUNKS.multipliedBy(TOTAL_CHUNKS).plusMillis(500).toMillis());

            assertEquals(CANCEL_AFTER, received.size(),
                    "tokens kept arriving after cancel: " + received);
            assertFalse(terminated[0],
                    "a cancelled subscriber must not receive a terminal signal");
        }
    }
}
