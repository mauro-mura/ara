package io.ara.runtime.llm;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * Separates reasoning that a model wrote <em>inside</em> its text from the answer, for an endpoint
 * that does not do it itself: a local server run without reasoning parsing, or a model that prints
 * {@code <think>…</think>} or raw gpt-oss "harmony" channels. The reasoning moves to
 * {@link LlmCompletion#reasoning()}; the text keeps only the answer.
 *
 * <p><b>Opt-in and per endpoint.</b> The caller names the style(s) when wrapping, because it is the
 * one that knows what the endpoint emits ({@code ReasoningExtractingLlmClient.wrap(client,
 * Style.THINK_TAGS)}). There is no detection. A server that already returns the reasoning in its
 * own field must not be wrapped: the adapter has read it, and there would be nothing left here to
 * find. If a completion already carries reasoning and the text carries more, both are kept, the
 * server's first.
 *
 * <p><b>Streaming.</b> The tokens that reach the caller go through a {@link ReasoningSplitter},
 * which holds back the few characters that could be the start of a marker and never lets the
 * reasoning through. Demand is kept honest: a token that is fully swallowed is compensated with
 * one more {@code request(1)} upstream, so a bounded subscriber is not starved. The completion
 * handed to {@code completionSink} (the full text, at the end) is split with a fresh splitter, so
 * the sink sees the same text and reasoning a blocking call would.
 *
 * <p>Thread-safe: each call builds its own splitter.
 */
public final class ReasoningExtractingLlmClient extends DelegatingLlmClient {

    /** The inline formats this decorator can undo. */
    public enum Style {
        /** {@code <think>…</think>}. */
        THINK_TAGS,
        /** gpt-oss harmony channels, raw: {@code analysis} is the reasoning, {@code final} the answer. */
        HARMONY
    }

    private final Set<Style> styles;

    private ReasoningExtractingLlmClient(LlmClient delegate, Set<Style> styles) {
        super(delegate);
        this.styles = styles;
    }

    /** Wraps {@code client} for the given style(s); at least one is required. */
    public static LlmClient wrap(LlmClient client, Style first, Style... more) {
        Set<Style> styles = EnumSet.of(first);
        for (Style style : more) {
            styles.add(style);
        }
        return new ReasoningExtractingLlmClient(client, styles);
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        return extract(delegate.complete(messages, context));
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        LlmCallContext inner = context.hasCompletionSink()
                ? context.withCompletionSink(completion -> context.completionSink().accept(extract(completion)))
                : context;
        Flow.Publisher<String> upstream = delegate.stream(messages, inner);
        return subscriber -> upstream.subscribe(new Flow.Subscriber<>() {
            private final ReasoningSplitter splitter = new ReasoningSplitter(styles);
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                subscriber.onSubscribe(s);
            }

            @Override
            public void onNext(String token) {
                String visible = splitter.feed(token);
                if (visible.isEmpty()) {
                    subscription.request(1);   // this token produced nothing to show: ask for another
                } else {
                    subscriber.onNext(visible);
                }
            }

            @Override
            public void onError(Throwable t) {
                subscriber.onError(t);
            }

            @Override
            public void onComplete() {
                String rest = splitter.finish();
                if (!rest.isEmpty()) {
                    subscriber.onNext(rest);
                }
                subscriber.onComplete();
            }
        });
    }

    private LlmCompletion extract(LlmCompletion completion) {
        ReasoningSplitter splitter = new ReasoningSplitter(styles);
        String text = splitter.feed(completion.text()) + splitter.finish();
        String inline = splitter.reasoning();
        if (inline.isEmpty() && text.equals(completion.text())) {
            return completion;
        }
        String reasoning = join(completion.reasoning(), inline);
        return new LlmCompletion(text, completion.promptTokens(), completion.outputTokens(),
                completion.finishReason(), completion.toolCallJson(), completion.toolCallId(),
                completion.toolCalls(), completion.tokensEstimated(), reasoning);
    }

    /** The server's own reasoning first, then what was found inline; {@code null} when there is none. */
    private static String join(String fromServer, String inline) {
        boolean hasServer = fromServer != null && !fromServer.isBlank();
        if (hasServer && !inline.isEmpty()) return fromServer + "\n" + inline;
        if (hasServer) return fromServer;
        return inline.isEmpty() ? null : inline;
    }
}
