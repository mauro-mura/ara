package io.ara.runtime.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.hitl.ApprovalNotifier;
import io.ara.core.hitl.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ApprovalNotifier} that sends an HTTP POST to a configurable webhook URL.
 *
 * <p>The request body is the JSON serialisation of {@link ApprovalRequest}.
 * The call runs on a virtual thread (fire-and-forget) so it never blocks the agent.
 *
 * <p>Retry policy: up to {@value #DEFAULT_MAX_RETRIES} attempts with exponential
 * backoff starting at {@value #DEFAULT_BASE_DELAY_MS} ms. On 5xx or connection
 * errors the attempt is retried; on 4xx all retries are skipped. Failures after
 * all retries are logged and swallowed — the agent is never interrupted.
 *
 * <p>Usage:
 * <pre>{@code
 * var notifier = WebhookApprovalNotifier.builder()
 *     .url("https://approval.example.com/hitl")
 *     .header("Authorization", "Bearer " + token)
 *     .build();
 * }</pre>
 */
public class WebhookApprovalNotifier implements ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookApprovalNotifier.class);

    static final int DEFAULT_MAX_RETRIES = 3;
    static final long DEFAULT_BASE_DELAY_MS = 200L;
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final Map<String, String> headers;
    private final int maxRetries;
    private final long baseDelayMs;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    /** No-arg constructor reads webhook URL from {@code HITL_WEBHOOK_URL} env var. */
    public WebhookApprovalNotifier() {
        this(System.getenv().getOrDefault("HITL_WEBHOOK_URL", "http://localhost:8080/hitl/approval"),
                Collections.emptyMap(), DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MS,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    private WebhookApprovalNotifier(String url, Map<String, String> headers,
                                    int maxRetries, long baseDelayMs,
                                    Duration connectTimeout, Duration requestTimeout) {
        this.url = Objects.requireNonNull(url, "url must not be null");
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public void notify(ApprovalRequest request) {
        String body;
        try {
            body = MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise ApprovalRequest: requestId={}", request.requestId(), e);
            return;
        }
        Thread.ofVirtual()
                .name("ara-hitl-webhook-" + request.requestId())
                .start(() -> sendWithRetry(request.requestId(), body));
    }

    void sendWithRetry(String requestId, String body) {
        long delayMs = baseDelayMs;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(requestTimeout);
                headers.forEach(builder::header);

                HttpResponse<Void> response = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.discarding());

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    log.debug("Webhook notified: requestId={}, status={}", requestId, status);
                    return;
                }
                if (status >= 400 && status < 500) {
                    log.error("Webhook rejected ({}): requestId={} — no retry for 4xx", status, requestId);
                    return;
                }
                log.warn("Webhook returned {}: requestId={}, attempt={}/{} — retrying in {}ms",
                        status, requestId, attempt, maxRetries, delayMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Webhook interrupted: requestId={}", requestId);
                return;
            } catch (Exception e) {
                Thread.interrupted(); // clear flag set by HttpClient on timeout
                log.warn("Webhook error: requestId={}, attempt={}/{} — {}",
                        requestId, attempt, maxRetries, e.getMessage());
            }

            if (attempt < maxRetries) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delayMs *= 2;
            }
        }
        log.error("Webhook failed after {} attempts: requestId={}", maxRetries, requestId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String url;
        private final Map<String, String> headers = new HashMap<>();
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long baseDelayMs = DEFAULT_BASE_DELAY_MS;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Builder() {}

        public Builder url(String url)                           { this.url = url; return this; }
        public Builder header(String name, String value)         { headers.put(name, value); return this; }
        public Builder headers(Map<String, String> h)            { headers.putAll(h); return this; }
        public Builder maxRetries(int v)                         { this.maxRetries = v; return this; }
        public Builder baseDelayMs(long v)                       { this.baseDelayMs = v; return this; }
        public Builder connectTimeout(Duration v)                { this.connectTimeout = Objects.requireNonNull(v); return this; }
        public Builder requestTimeout(Duration v)                { this.requestTimeout = Objects.requireNonNull(v); return this; }

        public WebhookApprovalNotifier build() {
            Objects.requireNonNull(url, "url must be set");
            return new WebhookApprovalNotifier(url, headers, maxRetries, baseDelayMs,
                    connectTimeout, requestTimeout);
        }
    }
}
