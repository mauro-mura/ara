package io.ara.adapters.llm;

import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.ara.core.llm.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link TokenStreamPublisher}'s subscription protocol by hand: the test holds the
 * langchain4j handler the publisher passed to the streaming call, so it can deliver tokens and
 * terminal signals at exactly the moments that matter — including after a cancel, which no
 * real provider can be asked to do on cue.
 *
 * <p>{@link TokenStreamPublisherCancellationTest} covers the same guarantee end to end through
 * a real adapter and a real HTTP stream; this class is where the edges live.
 */
class TokenStreamPublisherTest {

    /** Captures everything a subscriber was told, and lets a test cancel mid-stream. */
    private static final class RecordingSubscriber implements Flow.Subscriber<String> {
        final List<String> tokens = new ArrayList<>();
        Flow.Subscription subscription;
        boolean completed;
        Throwable error;

        @Override public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }
        @Override public void onNext(String token) { tokens.add(token); }
        @Override public void onError(Throwable t) { error = t; }
        @Override public void onComplete()         { completed = true; }
    }

    private AtomicReference<StreamingChatResponseHandler> handler;
    private RecordingSubscriber subscriber;

    /** Subscribes to a publisher whose streaming call does nothing but hand back its handler. */
    @BeforeEach
    void setUp() {
        handler = new AtomicReference<>();
        subscriber = new RecordingSubscriber();
        TokenStreamPublisher.of(handler::set, TokenStreamPublisherTest::asLlmException)
                .subscribe(subscriber);
    }

    private static LlmException asLlmException(Throwable t) {
        return LlmException.networkError("test-provider", t.getMessage(), t);
    }

    private StreamingChatResponseHandler provider() {
        assertNotNull(handler.get(), "the publisher never started the streaming call");
        return handler.get();
    }

    // ── Normal delivery ───────────────────────────────────────────────────────

    @Test
    void deliversTokensInOrderThenCompletes() {
        provider().onPartialResponse("Hello");
        provider().onPartialResponse(", ");
        provider().onPartialResponse("world");
        provider().onCompleteResponse(null);

        assertEquals(List.of("Hello", ", ", "world"), subscriber.tokens);
        assertTrue(subscriber.completed);
        assertNotNull(subscriber.subscription);
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    void stopsDeliveringTokensAfterCancel() {
        provider().onPartialResponse("kept");
        subscriber.subscription.cancel();
        provider().onPartialResponse("dropped");
        provider().onPartialResponse("also dropped");

        assertEquals(List.of("kept"), subscriber.tokens);
    }

    @Test
    void doesNotSignalCompletionAfterCancel() {
        subscriber.subscription.cancel();
        provider().onCompleteResponse(null);

        assertFalse(subscriber.completed, "a cancelled subscriber must not be told the stream ended");
    }

    @Test
    void doesNotSignalErrorAfterCancel() {
        subscriber.subscription.cancel();
        provider().onError(new RuntimeException("late failure"));

        assertNull(subscriber.error);
    }

    // ── One terminal signal, whatever the provider does ───────────────────────

    @Test
    void ignoresAnErrorArrivingAfterCompletion() {
        provider().onCompleteResponse(null);
        provider().onError(new RuntimeException("late failure"));

        assertTrue(subscriber.completed);
        assertNull(subscriber.error);
    }

    @Test
    void ignoresCompletionArrivingAfterAnError() {
        provider().onError(new RuntimeException("boom"));
        provider().onCompleteResponse(null);

        assertNotNull(subscriber.error);
        assertFalse(subscriber.completed);
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    @Test
    void mapsProviderErrorsThroughTheAdaptersMapper() {
        provider().onError(new RuntimeException("connection reset"));

        LlmException mapped = assertInstanceOf(LlmException.class, subscriber.error);
        assertEquals("test-provider", mapped.provider());
    }

    @Test
    void reportsAFailureToStartTheStreamThroughOnError() {
        RecordingSubscriber failing = new RecordingSubscriber();
        TokenStreamPublisher.of(
                h -> { throw new IllegalStateException("could not build the request"); },
                TokenStreamPublisherTest::asLlmException).subscribe(failing);

        // Not thrown at the caller: a consumer that waits for a terminal signal (as the
        // runtime's streamAndCollect does) would otherwise wait for one that never comes.
        assertInstanceOf(LlmException.class, failing.error);
        assertTrue(failing.error.getMessage().contains("could not build the request"));
    }
}
