package io.ara.core.llm;

import io.ara.core.agent.AgentConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P0 row 6, §4 U6 — regression tests for
 * {@link LlmClient}'s default {@link LlmClient#stream}.
 *
 * <p>Before U6, {@code request(n)} ran {@link LlmClient#complete} synchronously on the
 * calling thread. A caller such as {@code ReactExecutionSupport.streamAndCollect} (in {@code
 * ara-runtime}) bounds its wait for the stream to finish only <em>after</em> {@code
 * subscribe()} returns — a blocking {@code request(n)} ran before that bound ever started,
 * silently defeating it for every client relying on this default rather than overriding
 * {@code stream()} with real streaming support. These tests exercise the {@code
 * Flow.Publisher} contract directly, without needing {@code ara-runtime} (which this module
 * must not depend on): {@code request(n)} must return before {@code complete()} does, and
 * {@code cancel()} must interrupt the in-flight call.
 */
class LlmClientDefaultStreamTest {

    private static final AgentConfig CONFIG = AgentConfig.defaults()
            .primaryLlm(LlmProfile.of("stub")).build();

    private static LlmCallContext ctx() {
        return LlmCallContext.from(CONFIG);
    }

    /** Blocks in {@code complete()} until released, so a test can observe whether the caller waited. */
    private static final class BlockingCompleteClient implements LlmClient {
        final CountDownLatch completeStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interruptedSignal = new CountDownLatch(1);

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            completeStarted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interruptedSignal.countDown();
                Thread.currentThread().interrupt();
                return new LlmCompletion("interrupted", 0, 0, "stop", null);
            }
            return new LlmCompletion("done", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "blocking-complete-stub"; }
    }

    @Test
    void requestReturnsBeforeCompleteFinishes_insteadOfBlockingTheCallingThread() throws InterruptedException {
        BlockingCompleteClient llm = new BlockingCompleteClient();
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        llm.stream(List.of(new LlmMessage("user", "hi")), ctx()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(String item) { received.set(item); }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });

        // request() must have returned already — complete() is still blocked on `release`,
        // so onComplete/onError must not have fired yet (done's count stays at 1).
        assertTrue(llm.completeStarted.await(5, TimeUnit.SECONDS), "complete() never started");
        assertEquals(1, done.getCount(), "request() must not block the calling thread until complete() returns");

        llm.release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "onComplete/onError never fired after release");
        assertEquals("done", received.get());
    }

    @Test
    void cancelInterruptsTheInFlightCompleteCall() throws InterruptedException {
        BlockingCompleteClient llm = new BlockingCompleteClient();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        llm.stream(List.of(new LlmMessage("user", "hi")), ctx()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { subscription.set(s); s.request(1); }
            @Override public void onNext(String item) { }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });

        assertTrue(llm.completeStarted.await(5, TimeUnit.SECONDS), "complete() never started");
        subscription.get().cancel();

        assertTrue(llm.interruptedSignal.await(5, TimeUnit.SECONDS),
                "cancel() must interrupt the worker thread running complete()");
    }

    @Test
    void aNormalNonBlockingCompleteCall_stillEmitsTheFullText() throws InterruptedException {
        LlmClient llm = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("hello world", 3, 3, "stop", null);
            }
            @Override public String providerId() { return "plain-stub"; }
        };
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        llm.stream(List.of(new LlmMessage("user", "hi")), ctx()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(String item) { received.set(item); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { done.countDown(); }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("hello world", received.get());
    }

    @Test
    void aThrowingCompleteCall_surfacesAsOnError() throws InterruptedException {
        LlmClient llm = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                throw LlmException.networkError("test-provider", "boom", null);
            }
            @Override public String providerId() { return "throwing-stub"; }
        };
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        llm.stream(List.of(new LlmMessage("user", "hi")), ctx()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(String item) { }
            @Override public void onError(Throwable t) { error.set(t); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(LlmException.class, error.get().getClass());
    }

    @Test
    void repeatedRequestCalls_runCompleteAtMostOnce() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        LlmClient llm = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                calls.incrementAndGet();
                return new LlmCompletion("x", 1, 1, "stop", null);
            }
            @Override public String providerId() { return "count-stub"; }
        };
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        llm.stream(List.of(new LlmMessage("user", "hi")), ctx()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { subscription.set(s); s.request(1); }
            @Override public void onNext(String item) { }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));

        subscription.get().request(1);   // a second request() — this publisher emits exactly one item
        Thread.sleep(Duration.ofMillis(100).toMillis());   // let a wrongly-spawned second worker have a chance to run

        assertEquals(1, calls.get(), "complete() must run at most once, however many times request() is called");
    }
}
