package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A client built on demand by an {@code LlmClientFactory} — the ADR-039 dynamic-transport
 * path — must reach its attachments' bytes just like a statically registered one.
 *
 * <p>This is the case that breaks if the media resolver is attached where the runtime wraps
 * its <em>registered</em> clients: a client the transport registry builds for an inline
 * profile is never in that map, so an agent configured this way would fail with "no
 * MediaStore is wired" while one was wired all along. Wrapping in the wiring factory, which
 * every agent's effective client passes through, is what makes both paths behave the same.
 */
class AraRuntimeDynamicTransportMediaTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 contract".getBytes(StandardCharsets.UTF_8);

    /** Resolves the bytes of whatever it is sent, so the test can see whether it could. */
    private static final class ResolvingClient implements LlmClient {
        final AtomicReference<byte[]> seen = new AtomicReference<>();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            for (LlmMessage m : messages) {
                for (MediaRef ref : m.media()) {
                    seen.set(context.mediaResolver().bytesOf(ref));
                }
            }
            return new LlmCompletion("FINAL_ANSWER: read it", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "built-on-demand"; }
        @Override public Set<String> supportedMediaTypes() {
            return MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT);
        }
    }

    @Test
    void a_client_built_by_an_LlmClientFactory_can_resolve_task_media() {
        AgentId id = AgentId.of("inline-transport-agent");
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);
        ResolvingClient built = new ResolvingClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient("unused-default", new ResolvingClient())
                .llmClientFactory(t -> built)
                .mediaStore(store)
                .build();
        try {
            // An inline transport (baseUrl + modelName, no named client): the transport
            // registry has to build the client, which is exactly the path that bypasses
            // per-registered-client wrapping.
            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentId(id)
                    .agentType("t")
                    .primaryLlm(LlmProfile.builder()
                            .transportId("inline")
                            .baseUrl("http://example.test")
                            .modelName("some-model")
                            .build())
                    .plannerStrategy("react")
                    .maxIterations(3)
                    .build());

            AgentResponse r = agent.execute(AgentTask.of("read it", List.of(pdf)));

            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
            assertArrayEquals(PDF_BYTES, built.seen.get(),
                    "a dynamically built client must see the same MediaStore as a registered one");
        } finally {
            runtime.stop();
        }
    }
}
