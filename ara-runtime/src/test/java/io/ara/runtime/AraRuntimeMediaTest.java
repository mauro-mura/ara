package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for a single turn: an attachment on {@link AgentTask} must reach the client's
 * outgoing message list, with the runtime's {@link MediaStore} reachable through the call
 * context — and must <em>not</em> come back on the next turn of the same session.
 */
class AraRuntimeMediaTest {

    /** Records what actually reached the client, then answers. */
    private static final class CapturingClient implements LlmClient {
        final List<List<LlmMessage>> calls = new ArrayList<>();
        final List<byte[]> resolvedBytes = new ArrayList<>();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.add(List.copyOf(messages));
            for (LlmMessage m : messages) {
                for (MediaRef ref : m.media()) {
                    resolvedBytes.add(context.mediaResolver().bytesOf(ref));
                }
            }
            return new LlmCompletion("FINAL_ANSWER: the clause is compliant", 5, 5, "stop", null);
        }

        @Override public String providerId() { return "capturing"; }
        @Override public Set<String> supportedMediaTypes() {
            return MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT);
        }

        /** The media on the last user message of call {@code n}, oldest call first. */
        List<MediaRef> userMediaOfCall(int n) {
            List<LlmMessage> messages = calls.get(n);
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).role())) return messages.get(i).media();
            }
            return List.of();
        }
    }

    private static final byte[] PDF_BYTES = "%PDF-1.4 contract".getBytes(StandardCharsets.UTF_8);

    private static AgentConfig configFor(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))
                .plannerStrategy("react")
                .maxIterations(3)
                .maxConversationTurns(4)
                .build();
    }

    @Test
    void task_media_reaches_the_client_and_its_bytes_resolve_through_the_wired_store() {
        AgentId id = AgentId.of("media-agent");
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);
        CapturingClient client = new CapturingClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), client)
                .mediaStore(store)
                .build();
        try {
            AraAgent agent = runtime.createAgent(configFor(id));
            AgentResponse r = agent.execute(AgentTask.of("check the recess clause", List.of(pdf))
                    .withSessionId(SessionId.of("s1")));

            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
            assertEquals(List.of(pdf), client.userMediaOfCall(0));
            assertEquals(1, client.resolvedBytes.size());
            assertArrayEquals(PDF_BYTES, client.resolvedBytes.get(0));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void a_media_only_task_is_accepted_and_carries_no_text() {
        AgentId id = AgentId.of("media-only-agent");
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);
        CapturingClient client = new CapturingClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), client)
                .mediaStore(store)
                .build();
        try {
            AraAgent agent = runtime.createAgent(configFor(id));
            AgentResponse r = agent.execute(AgentTask.of("", List.of(pdf))
                    .withSessionId(SessionId.of("s1")));

            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
            assertEquals(List.of(pdf), client.userMediaOfCall(0));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void replayed_turns_name_the_attachment_but_do_not_resend_it() {
        AgentId id = AgentId.of("replay-agent");
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);
        CapturingClient client = new CapturingClient();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), client)
                .mediaStore(store)
                .build();
        try {
            AraAgent agent = runtime.createAgent(configFor(id));
            SessionId session = SessionId.of("s1");

            agent.execute(AgentTask.of("check the recess clause", List.of(pdf)).withSessionId(session));
            agent.execute(AgentTask.of("and the notice period?").withSessionId(session));

            // Second call: the new turn carries nothing, and the replayed one is text.
            assertEquals(List.of(), client.userMediaOfCall(1),
                        "the new turn attached nothing of its own");
            assertTrue(client.calls.get(1).stream().noneMatch(LlmMessage::hasMedia),
                        "no message of the second call may re-attach the earlier document");
            assertTrue(client.calls.get(1).stream().anyMatch(m -> m.content().contains("contract.pdf")),
                        "the replayed turn must still say a document was attached");
            assertEquals(1, client.resolvedBytes.size(),
                        "the payload is paid for once, on the turn that introduced it");

            // The reference is still on the persisted turn, so the file stays retrievable.
            List<ConversationTurn> turns = runtime.conversationHistory(id, session);
            assertEquals(List.of(pdf), turns.get(0).media());
            assertEquals(List.of(), turns.get(1).media());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void unsupported_media_fails_the_task_instead_of_answering_without_the_document() {
        AgentId id = AgentId.of("text-only-agent");
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);

        // A client that declares no media support at all, exactly like a text-only provider.
        LlmClient textOnly = new LlmClient() {
            @Override
            public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                for (LlmMessage m : messages) {
                    if (m.hasMedia()) {
                        throw io.ara.core.llm.LlmException.unsupportedMediaType(
                                "text-only", m.media().get(0).mimeType(), m.media().get(0).name(), Set.of());
                    }
                }
                return new LlmCompletion("FINAL_ANSWER: sure", 1, 1, "stop", null);
            }
            @Override public String providerId() { return "text-only"; }
        };

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), textOnly)
                .mediaStore(store)
                .build();
        try {
            AraAgent agent = runtime.createAgent(configFor(id));
            AgentResponse r = agent.execute(AgentTask.of("check it", List.of(pdf))
                    .withSessionId(SessionId.of("s1")));

            assertFalse(r.isSuccess(),
                    "an answer here would be a confident statement about a document never sent");
        } finally {
            runtime.stop();
        }
    }
}
