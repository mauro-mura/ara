package io.ara.runtime;

import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P4, §4 U14 — regression test.
 *
 * <p>{@code AgentFactory}'s {@code llmTransports}/{@code mcpTransports}
 * {@code DefaultResourceRegistry} instances each own a scheduler thread for
 * {@code DrainPolicy.Forced} deadlines. Nothing previously called
 * {@code DefaultResourceRegistry#close()} on either when {@code AraRuntime.stop()} ran —
 * a leaked virtual thread per registry, per runtime, for the JVM's lifetime.
 */
class AraRuntimeFactoryCloseTest {

    @Test
    void stop_closesTheAgentFactorysTransportRegistries() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();
        runtime.start();

        assertFalse(runtime.factory().transportsAreClosed(),
                "the transport registries must still be open while the runtime is running");

        runtime.stop();

        assertTrue(runtime.factory().transportsAreClosed(),
                "stop() must close AgentFactory's transport registries, not just destroy agents");
    }
}
