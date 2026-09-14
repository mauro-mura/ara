package io.ara.adapters.embedding;

import java.util.List;

import io.ara.core.memory.EmbeddingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingVectorsTest {

    @Test
    void validate_returnsTheVectorUnchanged_whenSizeMatches() {
        List<Float> vector = List.of(1f, 2f, 3f);

        assertSame(vector, EmbeddingVectors.validate("openai", "text-embedding-3-small", vector, 3));
    }

    @Test
    void validate_throws_whenSizeDiffers() {
        List<Float> vector = List.of(1f, 2f, 3f);

        EmbeddingException ex = assertThrows(EmbeddingException.class,
                () -> EmbeddingVectors.validate("mistral", "mistral-embed", vector, 1024));

        assertFalse(ex.isRetryable());
        assertFalse(ex.shouldFailover(), "a persistent misconfiguration would recur on every candidate");
        assertEquals("mistral", ex.provider());
        assertTrue(ex.getMessage().contains("3"), ex.getMessage());
        assertTrue(ex.getMessage().contains("1024"), ex.getMessage());
    }
}
