package io.ara.runtime.artifact;

import io.ara.core.artifact.ArtifactExtractor;
import io.ara.core.artifact.ArtifactExtractor.Extracted;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactSinkTest {

    private static final ArtifactExtractor TWO_PARTS = content -> List.of(
            new Extracted("block-1.py", "text/plain", "print(1)".getBytes(StandardCharsets.UTF_8)),
            new Extracted("block-2.sql", "text/plain", "SELECT 1".getBytes(StandardCharsets.UTF_8)));

    @Test
    void theSharedDefaults_giveTheSinkThatDoesNothing() {
        assertSame(ArtifactSink.none(), ArtifactSink.of(ArtifactExtractor.none(), MediaStore.inMemory()));
        assertSame(ArtifactSink.none(), ArtifactSink.of(TWO_PARTS, MediaStore.noop()));
        assertEquals(List.of(), ArtifactSink.none().emit("```python\nprint(1)\n```"));
    }

    @Test
    void everyPartIsStored_andTheReferencesComeBackInOrder() {
        MediaStore store = MediaStore.inMemory();

        List<MediaRef> references = ArtifactSink.of(TWO_PARTS, store).emit("anything");

        assertEquals(List.of("block-1.py", "block-2.sql"), references.stream().map(MediaRef::name).toList());
        assertEquals("print(1)", new String(store.get(references.get(0).mediaId()).orElseThrow(), StandardCharsets.UTF_8));
        assertEquals("SELECT 1", new String(store.get(references.get(1).mediaId()).orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void theSameContentTwice_isOneEntryInTheStore() {
        MediaStore store = MediaStore.inMemory();
        ArtifactSink sink = ArtifactSink.of(TWO_PARTS, store);

        List<MediaRef> first = sink.emit("a");
        List<MediaRef> second = sink.emit("b");

        assertEquals(first.get(0).mediaId(), second.get(0).mediaId());
    }

    @Test
    void noContent_isNoArtifacts_andTheExtractorIsNotAsked() {
        AtomicInteger asked = new AtomicInteger();
        ArtifactSink sink = ArtifactSink.of(content -> {
            asked.incrementAndGet();
            return List.of();
        }, MediaStore.inMemory());

        assertEquals(List.of(), sink.emit(null));
        assertEquals(List.of(), sink.emit(""));
        assertEquals(0, asked.get());
    }

    @Test
    void anExtractorThatThrows_costsTheArtifactsAndNothingElse() {
        ArtifactSink sink = ArtifactSink.of(content -> {
            throw new IllegalStateException("boom");
        }, MediaStore.inMemory());

        assertEquals(List.of(), sink.emit("text"));
    }

    @Test
    void aStoreThatRefusesAPart_leavesNoPartialList() {
        List<String> accepted = new ArrayList<>();
        MediaStore refusingTheSecond = new MediaStore() {
            @Override public MediaRef put(String name, String mimeType, byte[] bytes) {
                if (accepted.size() == 1) {
                    throw new UnsupportedOperationException("full");
                }
                accepted.add(name);
                return MediaStore.inMemory().put(name, mimeType, bytes);
            }
            @Override public Optional<byte[]> get(String mediaId) { return Optional.empty(); }
            @Override public void delete(String mediaId) { }
        };

        assertEquals(List.of(), ArtifactSink.of(TWO_PARTS, refusingTheSecond).emit("text"),
                "one part stored and one refused is no list, not a list of one");
        assertEquals(List.of("block-1.py"), accepted);
    }

    @Test
    void aPartWhoseTypeTheStoreDoesNotAccept_isRefusedLikeAnyOtherFailure() {
        ArtifactExtractor wrongType = content ->
                List.of(new Extracted("x.bin", "application/x-unknown", new byte[]{1}));

        assertEquals(List.of(), ArtifactSink.of(wrongType, MediaStore.inMemory()).emit("text"));
    }
}
