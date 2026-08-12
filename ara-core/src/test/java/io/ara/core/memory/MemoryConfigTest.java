package io.ara.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryConfigTest {

    @Test
    void workingMemoryEviction_unspecified_defaultsToDropMiddle() {
        MemoryConfig cfg = new MemoryConfig(0, null, 0, 2, null);
        assertEquals("drop_middle", cfg.workingMemoryEviction());
    }

    @Test
    void workingMemoryEviction_isNotValidatedInCore_ownedByEvictionPolicyInRuntime() {
        // The closed set of valid eviction strategies lives in
        // io.ara.runtime.memory.EvictionPolicy, which ara-core cannot depend on.
        // Duplicating that validation here would mean keeping two independently
        // maintained copies of the same vocabulary in sync — so MemoryConfig stores
        // whatever it's given verbatim, and only the actual consumer rejects garbage.
        MemoryConfig cfg = new MemoryConfig(0, "not-a-real-policy", 0, 2, null);
        assertEquals("not-a-real-policy", cfg.workingMemoryEviction());
    }

    @Test
    void workingMemoryTokenBudget_negative_failsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryConfig(-1, "drop_middle", 0, 2, null));
    }

    @Test
    void maxConversationTurns_negative_failsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryConfig(0, "drop_middle", -1, 2, null));
    }

    @Test
    void defaults_matchTheDocumentedValues() {
        MemoryConfig cfg = MemoryConfig.defaults();
        assertEquals(0, cfg.workingMemoryTokenBudget());
        assertEquals("drop_middle", cfg.workingMemoryEviction());
        assertEquals(0, cfg.maxConversationTurns());
        assertEquals(2, cfg.maxReflections());
        assertNull(cfg.reflectionPrompt());
    }
}
