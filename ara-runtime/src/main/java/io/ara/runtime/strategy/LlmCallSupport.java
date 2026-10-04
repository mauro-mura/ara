package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Support for LLM calls with deadline enforcement and retry logic.
 * Extracted from ReactExecutionSupport to improve cohesion.
 */
final class LlmCallSupport {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmCallSupport.class);

    /** Attempts for one LLM call: the initial call plus retries. */
    private static final int LLM_MAX_ATTEMPTS = 4;
    /** First backoff delay; doubles per attempt up to {@link #LLM_RETRY_MAX_DELAY}. */
    private static final Duration LLM_RETRY_BASE_DELAY = Duration.ofMillis(500);
    /** Ceiling for a single backoff delay. */
    private static final Duration LLM_RETRY_MAX_DELAY = Duration.ofSeconds(8);

    private static final ScheduledExecutorService DEADLINE_WATCHDOG = deadlineWatchdog();

    private LlmCallSupport() {
        // utility
    }

    private static ScheduledExecutorService deadlineWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, Thread.ofVirtual().name("ara-llm-deadline").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static LlmCompletion completeWithRetry(
            LlmClient llm,
            List<LlmMessage> messages,
            LlmCallContext ctx,
            Instant deadline,
            AgentConfig config,
            String taskId) throws InterruptedException {

        for (int attempt = 1; attempt <= LLM_MAX_ATTEMPTS; attempt++) {
            try {
                return completeWithin(llm, messages, ctx, deadline, config);
            } catch (LlmException e) {
                if (!e.isRetryable() || attempt == LLM_MAX_ATTEMPTS) {
                    throw e;
                }
                Duration delay = backoff(attempt);
                if (log.isDebugEnabled()) {
                    log.debug("Retrying LLM call (attempt {}/{}) for task [{}] after {}ms due to {}",
                            attempt + 1, LLM_MAX_ATTEMPTS, taskId, delay.toMillis(), e.getClass().getSimpleName());
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw new LlmException("LLM call exhausted retries");
    }

    static LlmCompletion completeWithin(
            LlmClient llm,
            List<LlmMessage> messages,
            LlmCallContext ctx,
            Instant deadline,
            AgentConfig config) throws InterruptedException {

        long remainingMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        if (remainingMs <= 0) {
            throw new ExecutionTimeoutException(config.executionTimeout());
        }

        AtomicReference<LlmCompletion> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                resultRef.set(llm.complete(messages, ctx));
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        });

        ScheduledFuture<?> interruptFuture = null;
        try {
            interruptFuture = DEADLINE_WATCHDOG.schedule(worker::interrupt, remainingMs, TimeUnit.MILLISECONDS);
            if (!done.await(remainingMs, TimeUnit.MILLISECONDS)) {
                worker.interrupt();
                throw new ExecutionTimeoutException(config.executionTimeout());
            }
        } finally {
            if (interruptFuture != null) {
                interruptFuture.cancel(false);
            }
        }

        if (errorRef.get() != null) {
            Throwable t = errorRef.get();
            if (t instanceof LlmException le) {
                throw le;
            }
            if (t instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new LlmException(t.getMessage(), t);
        }
        return resultRef.get();
    }

    static LlmCompletion streamAndCollect(
            LlmClient llm,
            List<LlmMessage> messages,
            LlmCallContext ctx,
            AgentTask task,
            Instant deadline,
            AgentConfig config) throws InterruptedException {

        StringBuilder buf = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<java.util.concurrent.Flow.Subscription> subscription = new AtomicReference<>();

        llm.stream(messages, ctx).subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                subscription.set(s);
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String token) {
                buf.append(token);
                try {
                    if (task.tokenCallback() != null) {
                        task.tokenCallback().accept(token);
                    }
                } catch (Exception e) {
                    log.warn("Token callback threw for task [{}]: {}", task.taskId(), e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                err.set(t);
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        long remainingMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        try {
            if (!latch.await(remainingMs, TimeUnit.MILLISECONDS)) {
                cancelQuietly(subscription.get());
                throw new ExecutionTimeoutException(config.executionTimeout());
            }
        } catch (InterruptedException e) {
            cancelQuietly(subscription.get());
            throw e;
        }

        if (err.get() != null) {
            throw new LlmException("Streaming LLM call failed: " + err.get().getMessage(), err.get());
        }

        String text = buf.toString();
        if (text.isBlank()) {
            log.debug("Stream returned blank text — retrying with blocking complete() to recover tool-call metadata");
            return completeWithRetry(llm, messages, ctx, deadline, config, task.taskId());
        }

        int promptChars = 0;
        for (LlmMessage m : messages) {
            if (m.content() != null) {
                promptChars += m.content().length();
            }
        }
        int estPromptTokens = estimateTokensFromChars(promptChars);
        int estOutputTokens = estimateTokensFromChars(text.length());
        return new LlmCompletion(text, estPromptTokens, estOutputTokens, "stop", null, null, List.of(), true);
    }

    static void cancelQuietly(java.util.concurrent.Flow.Subscription subscription) {
        if (subscription == null) return;
        try {
            subscription.cancel();
        } catch (RuntimeException e) {
            log.debug("Stream subscription cancel() threw while aborting", e);
        }
    }

    private static Duration backoff(int attempt) {
        Duration d = LLM_RETRY_BASE_DELAY.multipliedBy(1L << Math.max(0, attempt - 1));
        if (d.compareTo(LLM_RETRY_MAX_DELAY) > 0) {
            d = LLM_RETRY_MAX_DELAY;
        }
        return d;
    }

    private static final int APPROX_CHARS_PER_TOKEN = 4;

    static int estimateTokensFromChars(int chars) {
        return chars <= 0 ? 0 : Math.max(1, chars / APPROX_CHARS_PER_TOKEN);
    }

    static String describeLlmFailure(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (!(e instanceof LlmException le)) {
            return message;
        }
        String provider = le.provider() != null ? "/" + le.provider() : "";
        return "LLM call failed [" + le.errorType() + provider + "]: " + message;
    }
}
