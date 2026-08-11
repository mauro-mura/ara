package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Validation and defaults for {@link StrategyConfig.ReflAct}. */
class StrategyConfigReflActTest {

    @Test
    void defaults_areThreeReflectionsStreakOfTwoReflectOnFailure() {
        StrategyConfig.ReflAct rc = StrategyConfig.ReflAct.defaults();
        assertEquals(3, rc.maxReflections());
        assertEquals(2, rc.unproductiveStreak());
        assertTrue(rc.reflectOnToolFailure());
        assertNull(rc.reflectionProvider());
        assertEquals("reflact", rc.strategyName());
    }

    @Test
    void maxReflections_rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyConfig.ReflAct(-1, 2, true, null));
    }

    @Test
    void maxReflectionsZero_isValid_disablesSelfCorrection() {
        assertDoesNotThrow(() -> new StrategyConfig.ReflAct(0, 2, true, null));
    }

    @Test
    void unproductiveStreak_rejectsLessThanOne() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyConfig.ReflAct(3, 0, true, null));
        assertThrows(IllegalArgumentException.class, () -> new StrategyConfig.ReflAct(3, -1, true, null));
    }

    @Test
    void staticFactory_reflact_returnsDefaults() {
        assertEquals(StrategyConfig.ReflAct.defaults(), StrategyConfig.reflact());
    }
}
