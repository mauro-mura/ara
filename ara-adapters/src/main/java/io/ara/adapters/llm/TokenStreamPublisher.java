package io.ara.adapters.llm;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.ara.core.llm.LlmException;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bridges a langchain4j streaming call to the {@link Flow.Publisher} that
 * {@code LlmClient.stream} returns, shared by every adapter so the subscription protocol is
 * implemented once.
 *
 * <h2>What cancellation does, and what it cannot do</h2>
 * <p>{@link Flow.Subscription#cancel()} stops delivery: after it returns, no further
 * {@code onNext}, {@code onComplete} or {@code onError} reaches the subscriber. It does
 * <em>not</em> abort the provider connection — {@code StreamingChatModel.chat} returns
 * {@code void} and hands back no handle to close, so the underlying HTTP stream drains on its
 * own and its remaining tokens are discarded here.
 *
 * <p>That distinction is the whole point of this class. The caller that cancels is
 * {@code ReactExecutionSupport.streamAndCollect}, on timeout or interrupt, and what it needs
 * is for tokens to stop arriving: every adapter previously left {@code cancel()} empty (or
 * cancelled a future nothing observed), so a timed-out task kept accumulating text and kept
 * pushing tokens to an SSE client long after the caller had been told it failed.
 *
 * <p>A token already in flight when {@code cancel()} runs may still be delivered — the flag is
 * read per emission, not held under a lock. Reactive Streams allows exactly this: cancellation
 * is best-effort and asynchronous. What is guaranteed is that delivery stops promptly and that
 * the subscriber sees at most one terminal signal.
 *
 * <h2>Demand</h2>
 * <p>{@link Flow.Subscription#request(long)} is a no-op: the provider pushes server-sent
 * events at its own pace and nothing here buffers them. The only consumer in the runtime
 * requests {@code Long.MAX_VALUE} as soon as it subscribes, so honouring demand would add a
 * buffer that is never used.
 */
public final class TokenStreamPublisher {

    private TokenStreamPublisher() {}

    /**
     * Wraps one streaming call as a publisher of its text tokens.
     *
     * @param startStreaming starts the call, passing it the handler to report into. Runs when
     *                       a subscriber arrives, not when this method is called; anything it
     *                       throws is reported through {@code onError} rather than to whoever
     *                       called {@code subscribe}
     * @param errorMapper    turns a provider failure into the adapter's {@link LlmException}
     * @return the publisher to hand back from {@code LlmClient.stream}
     */
    public static Flow.Publisher<String> of(
            Consumer<StreamingChatResponseHandler> startStreaming,
            Function<Throwable, LlmException> errorMapper) {

        return subscriber -> {
            // One flag for "cancelled" and "already terminated": both mean nothing more may be
            // delivered, and collapsing them is what keeps a late onError from following an
            // onComplete, or either from reaching a subscriber that has cancelled.
            AtomicBoolean stopped = new AtomicBoolean(false);

            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* push-based — see class javadoc */ }
                @Override public void cancel() { stopped.set(true); }
            });

            StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    if (!stopped.get()) subscriber.onNext(token);
                }
                @Override
                public void onCompleteResponse(ChatResponse response) {
                    if (stopped.compareAndSet(false, true)) subscriber.onComplete();
                }
                @Override
                public void onError(Throwable error) {
                    if (stopped.compareAndSet(false, true)) subscriber.onError(errorMapper.apply(error));
                }
            };

            try {
                startStreaming.accept(handler);
            } catch (Throwable t) {
                // Building the request or opening the connection failed. Reporting it through
                // onError keeps the subscriber's completion protocol intact — throwing out of
                // subscribe() instead would leave a caller that waits on a latch (as the
                // runtime does) with no signal to wait for.
                if (stopped.compareAndSet(false, true)) subscriber.onError(errorMapper.apply(t));
            }
        };
    }
}
