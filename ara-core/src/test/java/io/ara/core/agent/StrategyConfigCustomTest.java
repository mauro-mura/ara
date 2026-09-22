package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validation and copy semantics for {@link StrategyConfig.Custom}, the third-party seam. */
class StrategyConfigCustomTest {

    @Test
    void carriesTheStrategyNameAndItsParameters() {
        StrategyConfig.Custom c = new StrategyConfig.Custom("my-strategy", Map.of("depth", 4));
        assertEquals("my-strategy", c.strategyName());
        assertEquals(4, c.params().get("depth"));
    }

    @Test
    void nullParams_becomesEmptyMap() {
        StrategyConfig.Custom c = new StrategyConfig.Custom("my-strategy", null);
        assertTrue(c.params().isEmpty());
    }

    @Test
    void strategyName_rejectsNullAndBlank() {
        assertThrows(NullPointerException.class, () -> new StrategyConfig.Custom(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StrategyConfig.Custom("  ", Map.of()));
    }

    @Test
    void params_mapIsCopiedDefensively() {
        Map<String, Object> source = new HashMap<>();
        source.put("k", "v");
        StrategyConfig.Custom c = new StrategyConfig.Custom("my-strategy", source);
        source.put("k", "changed");
        assertEquals("v", c.params().get("k"), "the config must not observe later mutations of the source map");
        assertNotSame(source, c.params());
    }

    @Test
    void of_buildsAnEmptyParameterConfig() {
        StrategyConfig.Custom c = StrategyConfig.Custom.of("my-strategy");
        assertEquals("my-strategy", c.strategyName());
        assertTrue(c.params().isEmpty());
    }
}
