package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * {@link LlmClient} decorator that implements ordered failover across multiple clients.
 *
 * <p>On each call to {@link #complete}, it tries clients in declaration order.
 * {@link LlmException}s with {@link LlmException#isRetryable()} {@code false} (e.g.
 * authentication errors, invalid requests) are re-thrown immediately without attempting
 * further fallbacks. Retryable errors (rate-limits, network, 5xx) and generic
 * {@link RuntimeException}s advance to the next client in the list.
 * Only when all clients are exhausted is the last exception re-thrown.
 */
public final class FailoverLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FailoverLlmClient.class);

    private final List<LlmClient> clients;
    private final String          compositeId;
    private volatile String       lastSuccessfulProviderId;

    public FailoverLlmClient(List<LlmClient> clients) {
        Objects.requireNonNull(clients, "clients must not be null");
        if (clients.isEmpty()) throw new IllegalArgumentException("At least one client required");
        this.clients                  = List.copyOf(clients);
        this.compositeId              = buildCompositeId(clients);
        this.lastSuccessfulProviderId = clients.get(0).providerId();
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        LlmException     lastLlmFailure = null;
        RuntimeException lastFailure    = null;

        for (int i = 0; i < clients.size(); i++) {
            LlmClient candidate = clients.get(i);
            try {
                LlmCompletion result = candidate.complete(messages, context);
                if (i > 0) {
                    log.info("Failover succeeded with client '{}' (primary failed after {} attempt(s))",
                            candidate.providerId(), i);
                }
                lastSuccessfulProviderId = candidate.providerId();
                return result;
            } catch (LlmException ex) {
                if (!ex.isRetryable()) {
                    log.error("LLM client '{}' returned non-retryable error [{}] — aborting failover: {}",
                            candidate.providerId(), ex.errorType(), ex.getMessage());
                    throw ex;
                }
                lastLlmFailure = ex;
                boolean hasNext = i < clients.size() - 1;
                if (hasNext) {
                    log.warn("LLM client '{}' failed [{}] — switching to next fallback. Reason: {}",
                            candidate.providerId(), ex.errorType(), ex.getMessage());
                } else {
                    log.error("All {} LLM client(s) failed. Last error from '{}' [{}]: {}",
                            clients.size(), candidate.providerId(), ex.errorType(), ex.getMessage());
                }
            } catch (RuntimeException ex) {
                lastFailure = ex;
                boolean hasNext = i < clients.size() - 1;
                if (hasNext) {
                    log.warn("LLM client '{}' failed — switching to next fallback. Reason: {}",
                            candidate.providerId(), ex.getMessage());
                } else {
                    log.error("All {} LLM client(s) failed. Last error from '{}': {}",
                            clients.size(), candidate.providerId(), ex.getMessage());
                }
            }
        }

        if (lastLlmFailure != null) throw lastLlmFailure;
        throw lastFailure;
    }

    @Override
    public String providerId() {
        return compositeId;
    }

    @Override
    public String lastUsedProviderId() {
        return lastSuccessfulProviderId;
    }

    /**
     * {@code true} only if every candidate supports native tools — a fallback that
     * doesn't would otherwise silently lose tool-calling ability whenever failover
     * picks it, since text-based scaffolding would have already been omitted.
     */
    @Override
    public boolean supportsNativeTools() {
        return clients.stream().allMatch(LlmClient::supportsNativeTools);
    }

    private static String buildCompositeId(List<LlmClient> clients) {
        return "failover[" + clients.stream()
                .map(LlmClient::providerId)
                .reduce((a, b) -> a + "→" + b)
                .orElse("empty") + "]";
    }
}
