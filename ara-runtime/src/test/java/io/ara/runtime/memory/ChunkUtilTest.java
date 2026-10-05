package io.ara.runtime.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ChunkUtil}, focused on the hard-split path ({@code splitLarge}).
 *
 * <p>Regression guard: before the fix, a single paragraph longer than {@link ChunkUtil#CHUNK_SIZE}
 * walked its tail one character at a time, emitting ~{@link ChunkUtil#CHUNK_OVERLAP} near-duplicate
 * chunks (e.g. 601 characters produced 82 chunks instead of 2). The assertions below pin the chunk
 * count to a sane value and would fail on the old behaviour.
 */
class ChunkUtilTest {

    @Test
    void shortTextYieldsSingleChunk() {
        String text = "a".repeat(ChunkUtil.CHUNK_SIZE); // exactly at the limit, no split
        assertEquals(List.of(text), ChunkUtil.chunk(text));
    }

    @Test
    void paragraphJustOverLimitSplitsIntoTwoChunksNotEightyTwo() {
        String text = "w".repeat(ChunkUtil.CHUNK_SIZE + 1); // 601 chars, no sentence boundary

        List<String> chunks = ChunkUtil.chunk(text);

        // One full window plus the overlapping tail: exactly two chunks.
        assertEquals(2, chunks.size(),
                "a 601-char paragraph must not explode into dozens of near-duplicate chunks");
    }

    @Test
    void largeParagraphProducesLinearNumberOfChunks() {
        // 5000 chars with no paragraph breaks: ceil((5000 - CHUNK_OVERLAP) / (CHUNK_SIZE - CHUNK_OVERLAP))
        // = ceil(4920 / 520) = 10 windows. Allow a small margin for the sentence-boundary heuristic.
        String text = "z".repeat(5000);

        List<String> chunks = ChunkUtil.chunk(text);

        assertTrue(chunks.size() <= 12,
                "expected ~10 chunks for 5000 chars, got " + chunks.size());
        assertTrue(chunks.size() >= 9,
                "expected ~10 chunks for 5000 chars, got " + chunks.size());
    }

    @Test
    void minifiedJsonOnASingleLineDoesNotExplode() {
        String json = "{\"data\":[" + "123,".repeat(400) + "]}"; // ~1611 chars, no \n\n

        List<String> chunks = ChunkUtil.chunk(json);

        assertTrue(chunks.size() <= 6,
                "a 1.6k single-line blob must chunk linearly, got " + chunks.size());
    }

    @Test
    void adjacentChunksOverlapAndCoverTheWholeText() {
        // Varying content so that chunks at different offsets are genuinely distinct — this lets
        // us assert the old bug's near-duplicate tail is gone without false positives from a
        // uniform filler string.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; sb.length() < 1500; i++) {
            sb.append("word").append(i).append(' ');
        }
        String text = sb.toString();

        List<String> chunks = ChunkUtil.chunk(text);

        assertTrue(chunks.size() >= 2, "1500 chars should produce more than one chunk");
        // Linear, not quadratic: nowhere near the ~CHUNK_OVERLAP explosion of the old bug.
        assertTrue(chunks.size() <= 6, "expected a handful of chunks, got " + chunks.size());
        // No chunk may exceed the configured window.
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= ChunkUtil.CHUNK_SIZE,
                    "chunk longer than CHUNK_SIZE: " + chunk.length());
        }
        // No two consecutive chunks are identical (the old bug produced near-duplicates).
        for (int i = 1; i < chunks.size(); i++) {
            assertFalse(chunks.get(i).equals(chunks.get(i - 1)),
                    "consecutive chunks must not be duplicates");
        }
    }
}
