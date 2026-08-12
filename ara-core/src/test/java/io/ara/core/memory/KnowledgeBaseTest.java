package io.ara.core.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseTest {

    private static final EmbeddingConfig EMBED =
            new EmbeddingConfig("http://localhost:11434", "key", "nomic-embed-text", 768);

    private static KnowledgeBase kb(int maxResults, float threshold) {
        return new KnowledgeBase("kb-1", "Support KB", EMBED, "kb-1_768d",
                Instant.now(), KnowledgeBase.STORE_QDRANT, maxResults, threshold);
    }

    @Test
    void validRetrievalParams_areKeptVerbatim() {
        KnowledgeBase kb = kb(10, 0.5f);
        assertEquals(10, kb.defaultMaxResults());
        assertEquals(0.5f, kb.scoreThreshold());
    }

    @Test
    void defaultMaxResults_outOfRange_failsFastInsteadOfSilentlyDefaulting() {
        // Regression guard: these used to be silently replaced with the default, so a
        // caller asking for 999 results got 3 with no error — the misconfiguration only
        // surfaced later as unexpected search behavior.
        assertThrows(IllegalArgumentException.class, () -> kb(0, 0.7f));
        assertThrows(IllegalArgumentException.class, () -> kb(-1, 0.7f));
        assertThrows(IllegalArgumentException.class, () -> kb(21, 0.7f),
                "the documented 1–20 upper bound must actually be enforced");
    }

    @Test
    void scoreThreshold_outOfRange_failsFastInsteadOfSilentlyDefaulting() {
        assertThrows(IllegalArgumentException.class, () -> kb(3, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> kb(3, 1.1f));
    }

    @Test
    void retrievalParamBounds_areInclusive() {
        assertDoesNotThrow(() -> kb(1, 0f));
        assertDoesNotThrow(() -> kb(20, 1f));
    }

    @Test
    void storeType_staysAnOpenKey_unknownValuesAreNotRejectedByCore() {
        // Which stores exist is resolved at wiring time, not enumerated in ara-core —
        // same convention as plannerStrategy/retrieverId/transportId.
        KnowledgeBase kb = new KnowledgeBase("kb-1", "KB", EMBED, "c",
                Instant.now(), "weaviate", 3, 0.7f);
        assertEquals("weaviate", kb.storeType());
        assertFalse(kb.isInMemory());
    }

    @Test
    void storeType_unspecified_defaultsToQdrant() {
        for (String unspecified : new String[]{null, "", "   "}) {
            KnowledgeBase kb = new KnowledgeBase("kb-1", "KB", EMBED, "c",
                    Instant.now(), unspecified, 3, 0.7f);
            assertEquals(KnowledgeBase.STORE_QDRANT, kb.storeType());
        }
    }

    @Test
    void isInMemory_reflectsTheInMemoryStoreType() {
        KnowledgeBase inMem = new KnowledgeBase("kb-1", "KB", EMBED, "c",
                Instant.now(), KnowledgeBase.STORE_IN_MEMORY);
        assertTrue(inMem.isInMemory());
    }

    @Test
    void requiredFields_areValidated() {
        assertThrows(NullPointerException.class, () -> new KnowledgeBase(
                null, "KB", EMBED, "c", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBase(
                "  ", "KB", EMBED, "c", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBase(
                "kb-1", "  ", EMBED, "c", Instant.now()));
    }

    @Test
    void convenienceConstructors_applyRetrievalDefaults() {
        KnowledgeBase kb = new KnowledgeBase("kb-1", "KB", EMBED, "c", Instant.now());
        assertEquals(KnowledgeBase.DEFAULT_MAX_RESULTS, kb.defaultMaxResults());
        assertEquals(KnowledgeBase.DEFAULT_SCORE_THRESHOLD, kb.scoreThreshold());
        assertEquals(KnowledgeBase.STORE_QDRANT, kb.storeType());
    }

    @Test
    void collectionName_derivesFromKbIdAndDimensions() {
        assertEquals("kb-1_768d", KnowledgeBase.collectionName("kb-1", 768));
    }
}
