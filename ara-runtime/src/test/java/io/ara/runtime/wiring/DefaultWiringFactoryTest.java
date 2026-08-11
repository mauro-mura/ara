package io.ara.runtime.wiring;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmClientFactory;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.llm.LlmTransport;
import io.ara.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWiringFactoryTest {

    /** Minimal stub client identified only by {@code providerId()}; never actually completes. */
    private static final class StubClient implements LlmClient {
        private final String id;
        StubClient(String id) { this.id = id; }
        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }
        @Override public String providerId() { return id; }
    }

    private static AgentConfig configWithPrimary(String transportId) {
        return AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.of(transportId))
                .build();
    }

    private static AgentConfig configWithPolicy(LlmSelectionPolicy policy, String primary, String... fallbacks) {
        return AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.of(primary))
                .fallbackLlms(List.of(fallbacks).stream().map(LlmProfile::of).toList())
                .llmSelectionPolicy(policy)
                .build();
    }

    /** Placeholder spec for a named (seeded) registry entry — publish() needs one, but our
     *  factory only ever builds distinguishable StubClients from the incrementing counter. */
    private static LlmTransport placeholderTransport(String modelName) {
        return new LlmTransport("http://placeholder", null, modelName);
    }

    private DefaultResourceRegistry<LlmTransport, LlmClient> registryWithNamedClients(AtomicInteger buildCount, String... ids) {
        DefaultResourceRegistry<LlmTransport, LlmClient> registry =
                new DefaultResourceRegistry<>(transport -> {
                    buildCount.incrementAndGet();
                    return new StubClient("built:" + transport.modelName());
                }, client -> { });
        for (String id : ids) {
            registry.seed(id, new StubClient(id));
        }
        return registry;
    }

    @Test
    void namedModelId_resolvesTransportFromRegistry() {
        var registry = registryWithNamedClients(new AtomicInteger(), "modelA");
        var factory = new DefaultWiringFactory(registry, "modelA", cfg -> ToolRegistry.empty());

        AgentWiring wiring = factory.build(configWithPrimary("modelA"));

        assertEquals("modelA", wiring.llm().providerId());
        assertEquals(1, wiring.leases().size());
        wiring.close();
    }

    @Test
    void sameModelId_differentTemperature_sharesTheSameTransportInstance() {
        var registry = registryWithNamedClients(new AtomicInteger(), "modelA");
        var factory = new DefaultWiringFactory(registry, "modelA", cfg -> ToolRegistry.empty());

        AgentConfig hot = AgentConfig.defaults().agentType("t")
                .primaryLlm(LlmProfile.builder().modelId("modelA").temperature(1.5).build()).build();
        AgentConfig cold = AgentConfig.defaults().agentType("t")
                .primaryLlm(LlmProfile.builder().modelId("modelA").temperature(0.0).build()).build();

        AgentWiring wiringHot  = factory.build(hot);
        AgentWiring wiringCold = factory.build(cold);

        assertSame(wiringHot.llm(), wiringCold.llm(),
                "parameters (asse B) must never cause a second transport to be built for the same modelId");
        wiringHot.close();
        wiringCold.close();
    }

    @Test
    void inlineOverride_sameContent_dedupesToOneTransportBuild() {
        AtomicInteger clientFactoryCalls = new AtomicInteger();
        LlmClientFactory clientFactory = transport -> {
            clientFactoryCalls.incrementAndGet();
            return new StubClient("inline-built");
        };
        DefaultResourceRegistry<LlmTransport, LlmClient> registry =
                new DefaultResourceRegistry<>(clientFactory::create, client -> { });
        var factory = new DefaultWiringFactory(registry, "default", cfg -> ToolRegistry.empty());

        LlmProfile inline = LlmProfile.builder()
                .modelId("ignored")
                .baseUrl("http://localhost:1234")
                .modelName("local-model")
                .apiKey("secret")
                .build();
        AgentConfig config = AgentConfig.defaults().agentType("t").primaryLlm(inline).build();

        AgentWiring first  = factory.build(config);
        AgentWiring second = factory.build(config);

        assertEquals(1, clientFactoryCalls.get(), "identical inline overrides must dedupe to a single built transport");
        assertSame(first.llm(), second.llm());
        first.close();
        second.close();
    }

    @Test
    void inlineOverride_differentApiKey_buildsDistinctTransports() {
        LlmClientFactory clientFactory = transport -> new StubClient("inline:" + transport.apiKey());
        DefaultResourceRegistry<LlmTransport, LlmClient> registry =
                new DefaultResourceRegistry<>(clientFactory::create, client -> { });
        var factory = new DefaultWiringFactory(registry, "default", cfg -> ToolRegistry.empty());

        LlmProfile profileA = LlmProfile.builder().modelId("x").baseUrl("http://h").modelName("m").apiKey("key-A").build();
        LlmProfile profileB = LlmProfile.builder().modelId("x").baseUrl("http://h").modelName("m").apiKey("key-B").build();

        AgentWiring wiringA = factory.build(AgentConfig.defaults().agentType("t").primaryLlm(profileA).build());
        AgentWiring wiringB = factory.build(AgentConfig.defaults().agentType("t").primaryLlm(profileB).build());

        assertNotEquals(wiringA.llm().providerId(), wiringB.llm().providerId());
        wiringA.close();
        wiringB.close();
    }

    @Test
    void failoverPolicy_buildsFailoverClientOverPinnedLeasesInProfileOrder() {
        var registry = registryWithNamedClients(new AtomicInteger(), "primary", "fallback1");
        var factory = new DefaultWiringFactory(registry, "primary", cfg -> ToolRegistry.empty());

        AgentWiring wiring = factory.build(configWithPolicy(LlmSelectionPolicy.FAILOVER, "primary", "fallback1"));

        assertTrue(wiring.llm().providerId().contains("primary"));
        assertTrue(wiring.llm().providerId().contains("fallback1"));
        assertEquals(2, wiring.leases().size(), "FAILOVER must lease every profile it can reach");
        wiring.close();
    }

    @Test
    void roundRobinPolicy_givesEachWiringItsOwnIndependentCounter() {
        var registry = registryWithNamedClients(new AtomicInteger(), "a", "b");
        var factory = new DefaultWiringFactory(registry, "a", cfg -> ToolRegistry.empty());
        AgentConfig config = configWithPolicy(LlmSelectionPolicy.ROUND_ROBIN, "a", "b");

        AgentWiring wiring1 = factory.build(config);
        AgentWiring wiring2 = factory.build(config);

        // Exhaust wiring1's round-robin counter across several calls...
        for (int i = 0; i < 3; i++) {
            wiring1.llm().complete(List.of(), (LlmCallContext) null);
        }
        // ...wiring2's own counter must still start fresh from index 0, independent of wiring1's state.
        wiring2.llm().complete(List.of(), (LlmCallContext) null);
        assertEquals("a", wiring2.llm().lastUsedProviderId());

        wiring1.close();
        wiring2.close();
    }

    @Test
    void primaryOnlyPolicy_leasesOnlyThePrimaryProfile() {
        var registry = registryWithNamedClients(new AtomicInteger(), "primary", "fallback1");
        var factory = new DefaultWiringFactory(registry, "primary", cfg -> ToolRegistry.empty());

        AgentWiring wiring = factory.build(configWithPolicy(LlmSelectionPolicy.PRIMARY_ONLY, "primary", "fallback1"));

        assertEquals(1, wiring.leases().size(), "PRIMARY_ONLY must never lease fallbacks");
        assertEquals("primary", wiring.llm().providerId());
        wiring.close();
    }

    @Test
    void transactionalBuild_rollsBackAlreadyAcquiredLeasesOnFailure() {
        AtomicInteger builds = new AtomicInteger();
        DefaultResourceRegistry<LlmTransport, LlmClient> registry = new DefaultResourceRegistry<>(transport -> {
            builds.incrementAndGet();
            if ("boom".equals(transport.modelName())) {
                throw new RuntimeException("transport build failed");
            }
            return new StubClient(transport.modelName());
        }, client -> { });
        registry.publish("primary", placeholderTransport("primary"));
        // "boom" is never seeded, so pin("boom") throws IllegalStateException before the factory
        // is even reached via pinOrCreate — force it through the inline-override path instead,
        // which does invoke the factory directly.
        var factory = new DefaultWiringFactory(registry, "primary", cfg -> ToolRegistry.empty());

        LlmProfile failingInline = LlmProfile.builder()
                .modelId("boom").baseUrl("http://h").modelName("boom").build();
        AgentConfig config = AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.of("primary"))
                .fallbackLlms(List.of(failingInline))
                .llmSelectionPolicy(LlmSelectionPolicy.FAILOVER)
                .build();

        assertThrows(RuntimeException.class, () -> factory.build(config));

        // The primary's transport must have been released, not left dangling: pinning it again
        // must resolve without error (its ref count should be back at a healthy state).
        Lease<LlmClient> reacquired = registry.pin("primary");
        assertEquals("primary", reacquired.get().providerId());
        reacquired.close();
    }

    @Test
    void logIoEnabled_wrapsWithLoggingLlmClient() {
        var registry = registryWithNamedClients(new AtomicInteger(), "primary");
        var factory = new DefaultWiringFactory(registry, "primary", cfg -> ToolRegistry.empty());

        AgentConfig config = AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.of("primary"))
                .logLlmIo(true)
                .build();

        AgentWiring wiring = factory.build(config);
        assertEquals(io.ara.runtime.llm.LoggingLlmClient.class, wiring.llm().getClass());
        wiring.close();
    }

    @Test
    void pinnedTransportVersion_keepsUsingThatVersion_evenAfterANewerOneIsPublished() {
        AtomicInteger buildCount = new AtomicInteger();
        DefaultResourceRegistry<LlmTransport, LlmClient> registry =
                new DefaultResourceRegistry<>(
                        transport -> new StubClient("built:" + transport.modelName() + "#" + buildCount.incrementAndGet()),
                        client -> { });
        long v1 = registry.publish("modelA", placeholderTransport("modelA"));
        var factory = new DefaultWiringFactory(registry, "modelA", cfg -> ToolRegistry.empty());

        AgentConfig pinnedConfig = AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.builder().modelId("modelA").pinnedTransportVersion(v1).build())
                .build();

        // Keep v1's ref count above zero so it's still resolvable once retired, then
        // publish v2 as the new current/unpinned-default version.
        Lease<LlmClient> keepAliveV1 = registry.pin("modelA", v1);
        registry.publish("modelA", placeholderTransport("modelA"));

        AgentWiring pinnedWiring = factory.build(pinnedConfig);
        assertEquals(1, pinnedWiring.leases().size());

        AgentConfig unpinnedConfig = configWithPrimary("modelA");
        AgentWiring unpinnedWiring = factory.build(unpinnedConfig);

        assertNotEquals(pinnedWiring.llm().providerId(), unpinnedWiring.llm().providerId(),
                "the explicitly pinned session must keep resolving to v1 while an unpinned one follows latest (v2)");

        keepAliveV1.close();
        pinnedWiring.close();
        unpinnedWiring.close();
    }

    @Test
    void pinnedTransportVersion_thatNeverExisted_throwsRatherThanFallingBackToDefault() {
        var registry = registryWithNamedClients(new AtomicInteger(), "modelA");
        var factory = new DefaultWiringFactory(registry, "modelA", cfg -> ToolRegistry.empty());

        AgentConfig config = AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.builder().modelId("modelA").pinnedTransportVersion(999L).build())
                .build();

        assertThrows(IllegalStateException.class, () -> factory.build(config));
    }
}
