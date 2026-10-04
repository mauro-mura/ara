package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.artifact.ArtifactExtractor;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import io.ara.runtime.artifact.FencedBlockArtifactExtractor;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The artifacts of an answer, through a whole runtime: the extractor splits the final answer, the store keeps
 * the parts, and the response carries references. Everything that is not switched on behaves as it always did.
 */
class AraRuntimeArtifactsTest {

    private static final String ANSWER = "Ecco il codice:\n```python\ndef f():\n    return 1\n```\nE la query:\n```sql\nSELECT 1;\n```";

    private static AraRuntime runtime(MediaStore store, ArtifactExtractor extractor, String answer) {
        AraRuntime.Builder builder = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer(answer).build());
        if (store != null) {
            builder.mediaStore(store);
        }
        if (extractor != null) {
            builder.artifactExtractor(extractor);
        }
        return builder.build();
    }

    private static AgentConfig config(String id) {
        return AgentConfig.defaults().agentId(AgentId.of(id)).agentType("t")
                .primaryLlm(LlmProfile.of("test-model")).plannerStrategy("react").maxIterations(3).build();
    }

    private static AgentResponse run(AraRuntime runtime, AgentContract contract) {
        AraAgent agent = contract == null ? runtime.createAgent(config("a")) : runtime.createAgent(config("a"), contract);
        try {
            return agent.execute(AgentTask.of("scrivi del codice"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void withAStoreAndAnExtractor_theResponseCarriesTheBlocksAsStoredReferences() {
        MediaStore store = MediaStore.inMemory();

        AgentResponse response = run(runtime(store, new FencedBlockArtifactExtractor(), ANSWER), null);

        assertTrue(response.isSuccess(), () -> response.failureReason());
        assertEquals(List.of("block-1.py", "block-2.sql"), response.artifacts().stream().map(MediaRef::name).toList());
        MediaRef first = response.artifacts().get(0);
        assertEquals("def f():\n    return 1", new String(store.get(first.mediaId()).orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(MediaStore.digestOf("def f():\n    return 1".getBytes(StandardCharsets.UTF_8)), first.mediaId(),
                "the reference is the digest of the content, like every other media reference");
        assertTrue(response.content().contains("```python"), "the text is unchanged: artifacts are in addition to it");
    }

    @Test
    void byDefault_thereAreNoArtifacts() {
        assertEquals(List.of(), run(runtime(null, null, ANSWER), null).artifacts());
    }

    @Test
    void aStoreWithoutAnExtractor_hasNone() {
        assertEquals(List.of(), run(runtime(MediaStore.inMemory(), null, ANSWER), null).artifacts());
    }

    @Test
    void anExtractorWithoutAStoreThatCanWrite_hasNone() {
        AgentResponse response = run(runtime(null, new FencedBlockArtifactExtractor(), ANSWER), null);

        assertTrue(response.isSuccess());
        assertEquals(List.of(), response.artifacts());
    }

    @Test
    void anAnswerWithNoBlock_hasNone() {
        assertEquals(List.of(), run(runtime(MediaStore.inMemory(), new FencedBlockArtifactExtractor(), "solo testo"), null)
                .artifacts());
    }

    @Test
    void anExtractorThatThrows_doesNotFailTheAgent() {
        AgentResponse response = run(runtime(MediaStore.inMemory(), content -> {
            throw new IllegalStateException("boom");
        }, ANSWER), null);

        assertTrue(response.isSuccess(), "the answer was complete; only its artifacts were lost");
        assertEquals(List.of(), response.artifacts());
        assertTrue(response.content().contains("def f()"));
    }

    @Test
    void aFailedTask_hasNoArtifacts() {
        AraRuntime failing = AraRuntime.builder()
                .llmClient(new io.ara.core.llm.LlmClient() {
                    @Override public io.ara.core.llm.LlmCompletion complete(List<io.ara.core.llm.LlmMessage> messages,
                                                                            io.ara.core.llm.LlmCallContext context) {
                        throw io.ara.core.llm.LlmException.invalidRequest("test", "no");
                    }
                    @Override public String providerId() { return "failing"; }
                })
                .mediaStore(MediaStore.inMemory())
                .artifactExtractor(new FencedBlockArtifactExtractor())
                .build();

        AgentResponse response = run(failing, null);

        assertFalse(response.isSuccess());
        assertEquals(List.of(), response.artifacts());
    }

    /**
     * The point of an artifact being a {@code MediaRef}: the output of one task is, unchanged, the attachment of the next.
     * The first call answers with a code block; the second receives that block as media and shows what it could read.
     */
    @Test
    void anArtifactOfOneTask_isTheAttachmentOfTheNext() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> seenByTheSecond = new java.util.concurrent.atomic.AtomicReference<>();
        io.ara.core.llm.LlmClient client = new io.ara.core.llm.LlmClient() {
            @Override public io.ara.core.llm.LlmCompletion complete(List<io.ara.core.llm.LlmMessage> messages,
                                                                    io.ara.core.llm.LlmCallContext context) {
                if (calls.incrementAndGet() == 1) {
                    return new io.ara.core.llm.LlmCompletion(
                            "Action: FINAL_ANSWER\nAnswer: ecco\n```python\nprint('ciao')\n```", 1, 1, "stop", null);
                }
                for (io.ara.core.llm.LlmMessage message : messages) {
                    for (MediaRef reference : message.media()) {
                        seenByTheSecond.set(new String(context.mediaResolver().bytesOf(reference), StandardCharsets.UTF_8));
                    }
                }
                return new io.ara.core.llm.LlmCompletion("Action: FINAL_ANSWER\nAnswer: letto", 1, 1, "stop", null);
            }
            @Override public String providerId() { return "chaining"; }
            @Override public java.util.Set<String> supportedMediaTypes() {
                return io.ara.core.media.MediaTypes.ofKinds(io.ara.core.media.MediaTypes.MediaKind.IMAGE,
                        io.ara.core.media.MediaTypes.MediaKind.DOCUMENT, io.ara.core.media.MediaTypes.MediaKind.TEXT);
            }
        };
        AraRuntime runtime = AraRuntime.builder().llmClient(client).mediaStore(MediaStore.inMemory())
                .artifactExtractor(new FencedBlockArtifactExtractor()).build();
        try {
            AraAgent writer = runtime.createAgent(config("writer"));
            AraAgent reader = runtime.createAgent(config("reader"));

            AgentResponse written = writer.execute(AgentTask.of("scrivi del codice"));
            AgentResponse read = reader.execute(AgentTask.of("leggi l'allegato", written.artifacts()));

            assertTrue(read.isSuccess(), () -> read.failureReason());
            assertTrue(seenByTheSecond.get().contains("print('ciao')"),
                    "the second task saw the first one's block: " + seenByTheSecond.get());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void anAgentWithAContract_keepsTheArtifactsOfAnAnswerItsProcessorsAccept() {
        AgentContract passing = AgentContract.builder().addOutputProcessor(ProcessingResult::pass).build();

        AgentResponse response = run(runtime(MediaStore.inMemory(), new FencedBlockArtifactExtractor(), ANSWER), passing);

        assertTrue(response.isSuccess(), () -> response.failureReason());
        assertEquals(2, response.artifacts().size());
    }
}
