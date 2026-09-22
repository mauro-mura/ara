package io.ara.examples.support;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline stand-in LLM that streams its answer word by word, for the no-network demos.
 *
 * <p>One class replaces the three near-identical streaming stubs that used to live in
 * {@code SimpleStreamingExample}, {@code StreamingWithToolExample} and
 * {@code StreamingChatWebExample}: the only thing that differs between them is the text
 * and the pacing. The text is computed from the messages by a {@code Function<List<LlmMessage>,
 * String>} supplied at construction (a canned sentence, a keyword-answerer, a two-turn
 * script); {@link #complete} returns the whole text at once — the blocking path, and the
 * blank-stream fallback that {@code streamAndCollect} uses to recover tool-call texts — while
 * {@link #stream} emits the same text in chunks, the way a real provider's SSE socket would.
 *
 * <p>Token counts in {@code LlmCompletion} are cosmetic estimates derived deterministically
 * from the text; no example prints them.
 */
public final class StreamingLlmStub implements LlmClient {

    /** Keeps whole words together with their trailing whitespace; whitespace runs are one chunk. */
    private static final Pattern CHUNK = Pattern.compile("\\S+\\s*|\\s+");

    private final Function<List<LlmMessage>, String> textFor;
    private final long                              tokenDelayMillis;
    private final String                            provider;

    /**
     * @param textFor          computes the full turn's text from the messages in context
     * @param tokenDelayMillis pause between emitted chunks ({@code 0} to stream as fast as possible)
     * @param providerId       reported by {@link #providerId()}, mirroring a real gateway's name
     */
    public StreamingLlmStub(Function<List<LlmMessage>, String> textFor,
                            long tokenDelayMillis, String providerId) {
        this.textFor = textFor;
        this.tokenDelayMillis = tokenDelayMillis;
        this.provider = providerId;
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
        String text = textFor.apply(messages);
        int words = text.split("\\s+").length;
        // Estimates only — proportional to the text, never printed by the demos.
        return new LlmCompletion(text, words, words / 3 + 1, "stop", null);
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        String text = textFor.apply(messages);
        return subscriber -> {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* push-based, like TokenStreamPublisher */ }
                @Override public void cancel() { cancelled.set(true); }
            });
            try {
                Matcher m = CHUNK.matcher(text);
                while (m.find()) {
                    if (cancelled.get()) return;
                    subscriber.onNext(m.group());
                    if (tokenDelayMillis > 0) Thread.sleep(tokenDelayMillis);
                }
                subscriber.onComplete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                subscriber.onError(e);
            } catch (RuntimeException e) {
                subscriber.onError(e);
            }
        };
    }

    @Override
    public String providerId() {
        return provider;
    }
}
