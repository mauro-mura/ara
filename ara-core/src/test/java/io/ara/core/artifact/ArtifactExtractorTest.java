package io.ara.core.artifact;

import io.ara.core.artifact.ArtifactExtractor.Extracted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactExtractorTest {

    @Test
    void none_findsNothing() {
        assertEquals(List.of(), ArtifactExtractor.none().extract("```python\nprint(1)\n```"));
        assertEquals(List.of(), ArtifactExtractor.none().extract(""));
    }

    @Test
    void anExtractedPart_needsANameAndContent() {
        assertThrows(NullPointerException.class, () -> new Extracted(null, "text/plain", new byte[]{1}));
        assertThrows(NullPointerException.class, () -> new Extracted("a", null, new byte[]{1}));
        assertThrows(NullPointerException.class, () -> new Extracted("a", "text/plain", null));
        assertThrows(IllegalArgumentException.class, () -> new Extracted(" ", "text/plain", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new Extracted("a", "text/plain", new byte[0]));
    }

    @Test
    void anExtractedPart_cannotBeChangedThroughTheArrayItWasBuiltFromOrReturns() {
        byte[] source = {1, 2, 3};
        Extracted part = new Extracted("a", "text/plain", source);

        source[0] = 9;
        part.bytes()[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, part.bytes());
    }
}
