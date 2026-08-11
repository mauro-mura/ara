package io.ara.runtime.wiring;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin {@link LlmClient} decorator over a fixed, already-leased list of clients.
 *
 * <p>Unlike the previous {@code DefaultLlmRouter}'s global {@code AtomicInteger} (shared
 * across every session on the agent), each instance of this class owns its own counter —
 * built once per {@link AgentWiring}, i.e. once per session, so distribution never leaks
 * across sessions (ADR-039 "Impatto sui collaboratori esistenti").
 */
final class RoundRobinLlmClient implements LlmClient {

    private final List<LlmClient> clients;
    private final String compositeId;
    private final AtomicInteger index = new AtomicInteger(0);
    private volatile String lastUsedProviderId;

    RoundRobinLlmClient(List<LlmClient> clients) {
        Objects.requireNonNull(clients, "clients must not be null");
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("At least one client required");
        }
        this.clients = List.copyOf(clients);
        this.compositeId = "round-robin[" + clients.stream()
                .map(LlmClient::providerId)
                .reduce((a, b) -> a + "," + b)
                .orElse("empty") + "]";
        this.lastUsedProviderId = clients.get(0).providerId();
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        LlmClient chosen = clients.get(Math.floorMod(index.getAndIncrement(), clients.size()));
        LlmCompletion result = chosen.complete(messages, context);
        lastUsedProviderId = chosen.providerId();
        return result;
    }

    @Override
    public String providerId() {
        return compositeId;
    }

    @Override
    public String lastUsedProviderId() {
        return lastUsedProviderId;
    }

    /** {@code true} only if every candidate supports native tools (same reasoning as {@code FailoverLlmClient}). */
    @Override
    public boolean supportsNativeTools() {
        return clients.stream().allMatch(LlmClient::supportsNativeTools);
    }
}
