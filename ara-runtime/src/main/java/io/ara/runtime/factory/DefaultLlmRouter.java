package io.ara.runtime.factory;

import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmClientFactory;
import io.ara.core.llm.LlmRouter;
import io.ara.runtime.llm.LoggingLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of {@link LlmRouter} (ADR-030).
 *
 * <p>Resolves the {@link LlmClient} to use for a given call by applying the
 * agent's {@link LlmSelectionPolicy}:
 * <ul>
 *   <li>{@code PRIMARY_ONLY}       — always use the primary client; no fallback</li>
 *   <li>{@code FAILOVER}           — try primary; on exception try each fallback in order</li>
 *   <li>{@code ROUND_ROBIN}        — distribute calls sequentially across all profiles</li>
 * </ul>
 *
 * <p>Client resolution order for each profile:
 * <ol>
 *   <li>Inline transport override: {@code profile.inlineTransport() != null} → {@link LlmClientFactory}</li>
 *   <li>Registry lookup by {@code transportId}</li>
 *   <li>Default registered client</li>
 * </ol>
 */
public final class DefaultLlmRouter implements LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmRouter.class);

    private final Map<String, LlmClient> registry;
    private final String                 defaultClientId;
    private final LlmClientFactory       clientFactory;
    private final AtomicInteger          roundRobinIndex = new AtomicInteger(0);

    public DefaultLlmRouter(Map<String, LlmClient> registry,
                            String defaultClientId,
                            LlmClientFactory clientFactory) {
        this.registry        = Map.copyOf(Objects.requireNonNull(registry));
        this.defaultClientId = Objects.requireNonNull(defaultClientId);
        this.clientFactory   = clientFactory;
    }

    @Override
    public LlmClient select(LlmConfig config, LlmCallContext callContext) {
        Objects.requireNonNull(config, "LlmConfig must not be null");

        LlmSelectionPolicy policy   = config.policy();
        List<LlmProfile>   all      = config.allProfiles();

        LlmClient client = switch (policy) {

            case FAILOVER -> {
                List<LlmClient> ordered = all.stream()
                        .map(p -> resolve(p, config))
                        .toList();
                yield ordered.size() == 1
                        ? ordered.get(0)
                        : new FailoverLlmClient(ordered);
            }

            case ROUND_ROBIN -> {
                int idx = roundRobinIndex.getAndIncrement() % all.size();
                yield resolve(all.get(idx), config);
            }

            default -> resolve(config.primary(), config);
        };

        return config.logIo() ? new LoggingLlmClient(client, config.logIoMaxChars()) : client;
    }

    private LlmClient resolve(LlmProfile profile, LlmConfig config) {
        // 1. Inline transport override (ADR-039 §3 asse A)
        if (clientFactory != null && profile.inlineTransport() != null) {
            log.debug("LlmRouter: using inline override model={}", profile.inlineTransport().modelName());
            return clientFactory.create(profile.inlineTransport());
        }

        // 2. Registry lookup by transportId
        String transportId = profile.transportId();
        if (transportId != null && !transportId.isBlank()) {
            LlmClient named = registry.get(transportId);
            if (named != null) {
                log.debug("LlmRouter: resolved '{}' from registry", transportId);
                return named;
            }
        }

        // 3. Default client
        LlmClient def = registry.get(defaultClientId);
        if (def != null) {
            log.debug("LlmRouter: using default client '{}'", defaultClientId);
            return def;
        }

        throw new IllegalStateException(
                "No LlmClient found for transportId='" + transportId
                + "' and no default registered (defaultClientId='" + defaultClientId + "')");
    }
}
