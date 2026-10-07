package io.ara.runtime.llm;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.runtime.llm.ReasoningExtractingLlmClient.Style;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Inline reasoning ({@code <think>}, harmony) is moved out of the text, in a blocking call and in a
 * stream, wherever the stream is cut. Offline: the "model" is a stub that replays fixed chunks.
 */
class ReasoningExtractingLlmClientTest {

    private static final String HARMONY = "<|channel|>analysis<|message|>2+2 is 4.<|end|>"
            + "<|start|>assistant<|channel|>final<|message|>The answer is 4.<|return|>";

    /** Replays {@code chunks} as a stream and their concatenation as a blocking completion. */
    private static final class Replay implements LlmClient {
        private final List<String> chunks;
        private final String reasoningFromServer;

        Replay(List<String> chunks, String reasoningFromServer) {
            this.chunks = chunks;
            this.reasoningFromServer = reasoningFromServer;
        }

        private LlmCompletion whole() {
            return new LlmCompletion(String.join("", chunks), 3, 4, "stop", null, null, List.of(), false,
                    reasoningFromServer);
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return whole();
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long n) {
                    if (done) return;
                    done = true;
                    chunks.forEach(subscriber::onNext);
                    if (context.hasCompletionSink()) context.completionSink().accept(whole());
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }

        @Override
        public String providerId() {
            return "replay";
        }
    }

    private static List<String> inPieces(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) out.add(text.substring(i, Math.min(text.length(), i + size)));
        return out;
    }

    private static String streamed(LlmClient client, LlmCallContext context) {
        StringBuilder out = new StringBuilder();
        client.stream(List.of(LlmMessage.user("q")), context).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(String t) { out.append(t); }
            @Override public void onError(Throwable t) { throw new AssertionError(t); }
            @Override public void onComplete() {}
        });
        return out.toString();
    }

    private static LlmCompletion blocking(LlmClient client) {
        return client.complete(List.of(LlmMessage.user("q")), new LlmCallContext.Builder().build());
    }

    @Test
    void thinkTags_moveTheReasoningOutOfTheText() {
        LlmClient client = ReasoningExtractingLlmClient.wrap(
                new Replay(List.of("<think>Two plus two.</think>\n\nIt is 4."), null), Style.THINK_TAGS);

        LlmCompletion c = blocking(client);

        assertEquals("It is 4.", c.text());
        assertEquals("Two plus two.", c.reasoning());
        assertEquals(3, c.promptTokens());
    }

    @Test
    void harmony_keepsTheFinalChannelAsTheText() {
        LlmCompletion c = blocking(ReasoningExtractingLlmClient.wrap(
                new Replay(List.of(HARMONY), null), Style.HARMONY));

        assertEquals("The answer is 4.", c.text());
        assertEquals("2+2 is 4.", c.reasoning());
    }

    @Test
    void aTextWithoutMarkers_comesBackUnchanged_andWithNoReasoning() {
        LlmCompletion original = new LlmCompletion("Just an answer.", 1, 1, "stop", null, null);
        LlmClient plain = new Replay(List.of("Just an answer."), null);

        LlmCompletion c = blocking(ReasoningExtractingLlmClient.wrap(plain, Style.THINK_TAGS, Style.HARMONY));

        assertEquals(original.text(), c.text());
        assertNull(c.reasoning());
    }

    @Test
    void aReasoningThatNeverCloses_staysReasoning_notAnAnswer() {
        LlmCompletion c = blocking(ReasoningExtractingLlmClient.wrap(
                new Replay(List.of("<think>I was still thinking when"), null), Style.THINK_TAGS));

        assertEquals("", c.text());
        assertEquals("I was still thinking when", c.reasoning());
    }

    @Test
    void theServersOwnReasoning_isKeptFirst_andTheInlineOneAdded() {
        LlmCompletion c = blocking(ReasoningExtractingLlmClient.wrap(
                new Replay(List.of("<think>inline</think>done"), "from the server"), Style.THINK_TAGS));

        assertEquals("from the server\ninline", c.reasoning());
        assertEquals("done", c.text());
    }

    @Test
    void streaming_neverShowsTheMarkup_wherever_theTokensAreCut() {
        String raw = "<think>Two plus two.</think>\n\nIt is 4.";
        for (int size = 1; size <= raw.length(); size++) {
            LlmClient client = ReasoningExtractingLlmClient.wrap(new Replay(inPieces(raw, size), null),
                    Style.THINK_TAGS);

            assertEquals("It is 4.", streamed(client, new LlmCallContext.Builder().build()), "cut every " + size);
        }
    }

    @Test
    void streaming_harmony_isSplitAtEveryCut_andMatchesTheBlockingResult() {
        for (int size = 1; size <= HARMONY.length(); size++) {
            LlmClient client = ReasoningExtractingLlmClient.wrap(new Replay(inPieces(HARMONY, size), null),
                    Style.HARMONY);

            assertEquals("The answer is 4.", streamed(client, new LlmCallContext.Builder().build()),
                    "cut every " + size);
        }
    }

    @Test
    void streaming_aLoneAngleBracketThatIsNotAMarker_isNotLost() {
        LlmClient client = ReasoningExtractingLlmClient.wrap(
                new Replay(List.of("if a <", " b then <th", "is is fine"), null), Style.THINK_TAGS);

        assertEquals("if a < b then <this is fine", streamed(client, new LlmCallContext.Builder().build()));
    }

    @Test
    void streaming_theCompletionSink_getsTheSplitCompletion() {
        AtomicReference<LlmCompletion> sunk = new AtomicReference<>();
        LlmCallContext context = new LlmCallContext.Builder().completionSink(sunk::set).build();
        LlmClient client = ReasoningExtractingLlmClient.wrap(
                new Replay(inPieces("<think>why</think>because", 3), null), Style.THINK_TAGS);

        streamed(client, context);

        assertEquals("because", sunk.get().text());
        assertEquals("why", sunk.get().reasoning());
    }

    @Test
    void aSwallowedToken_asksUpstreamForAnother_soABoundedSubscriberIsNotStarved() {
        List<Long> requests = new ArrayList<>();
        LlmClient client = ReasoningExtractingLlmClient.wrap(new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
            @Override public String providerId() { return "p"; }
            @Override
            public Flow.Publisher<String> stream(List<LlmMessage> m, LlmCallContext c) {
                return s -> s.onSubscribe(new Flow.Subscription() {
                    private int next;
                    private final List<String> chunks = List.of("<think>", "x", "</think>", "ok");
                    @Override public void request(long n) {
                        requests.add(n);
                        for (long i = 0; i < n && next < chunks.size(); i++) s.onNext(chunks.get(next++));
                        if (next == chunks.size()) s.onComplete();
                    }
                    @Override public void cancel() {}
                });
            }
        }, Style.THINK_TAGS);
        StringBuilder out = new StringBuilder();

        client.stream(List.of(LlmMessage.user("q")), new LlmCallContext.Builder().build())
                .subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription sub;
                    @Override public void onSubscribe(Flow.Subscription s) { sub = s; s.request(1); }
                    @Override public void onNext(String t) { out.append(t); }
                    @Override public void onError(Throwable t) {}
                    @Override public void onComplete() {}
                });

        assertEquals("ok", out.toString(), "requests: " + requests);
    }
}
