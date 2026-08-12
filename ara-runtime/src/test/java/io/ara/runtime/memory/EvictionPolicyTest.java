package io.ara.runtime.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvictionPolicyTest {

    @Test
    void from_parsesEachValidToken_caseInsensitively() {
        assertEquals(EvictionPolicy.DROP_OLDEST, EvictionPolicy.from("drop_oldest"));
        assertEquals(EvictionPolicy.DROP_OLDEST, EvictionPolicy.from("DROP_OLDEST"));
        assertEquals(EvictionPolicy.DROP_MIDDLE, EvictionPolicy.from("drop_middle"));
        assertEquals(EvictionPolicy.SUMMARIZE,   EvictionPolicy.from("summarize"));
        assertEquals(EvictionPolicy.DROP_MIDDLE, EvictionPolicy.from("  Drop_Middle  "));
    }

    @Test
    void from_unspecified_defaultsToDropMiddle() {
        assertEquals(EvictionPolicy.DROP_MIDDLE, EvictionPolicy.from(null));
        assertEquals(EvictionPolicy.DROP_MIDDLE, EvictionPolicy.from(""));
        assertEquals(EvictionPolicy.DROP_MIDDLE, EvictionPolicy.from("   "));
    }

    @Test
    void from_rejectsUnknownToken() {
        // The single source of truth for this vocabulary — MemoryConfig (ara-core) no
        // longer duplicates this validation, since it can't depend on this enum.
        assertThrows(IllegalArgumentException.class, () -> EvictionPolicy.from("not-a-real-policy"));
    }
}
