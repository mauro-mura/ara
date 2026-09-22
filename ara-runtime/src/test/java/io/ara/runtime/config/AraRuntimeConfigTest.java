package io.ara.runtime.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AraRuntimeConfig} is now the single carrier of runtime identity and tuning
 * knobs ({@code name}, {@code startupTimeoutSec}, {@code shutdownTimeoutSec}) plus the
 * explicitly reserved persistence settings — its validation, defaults and YAML
 * mapping were previously untested.
 */
class AraRuntimeConfigTest {

    @Test
    void defaults_populateEveryDocumentedField() {
        AraRuntimeConfig c = AraRuntimeConfig.defaults();

        assertEquals(AraRuntimeConfig.DEFAULT_NAME, c.name());
        assertEquals(AraRuntimeConfig.DEFAULT_STARTUP_TIMEOUT, c.startupTimeoutSec());
        assertEquals(AraRuntimeConfig.DEFAULT_SHUTDOWN_TIMEOUT, c.shutdownTimeoutSec());
        assertEquals(AraRuntimeConfig.DEFAULT_PERSISTENCE_MODE, c.persistenceMode());
        assertEquals(AraRuntimeConfig.DEFAULT_H2_DB_PATH, c.h2DbPath());
        assertEquals("", c.description());
    }

    @Test
    void builder_roundTripsEveryField() {
        AraRuntimeConfig c = AraRuntimeConfig.builder()
                .name("edge")
                .startupTimeoutSec(5)
                .shutdownTimeoutSec(3)
                .persistenceMode("h2")
                .h2DbPath("./data/other")
                .description("edge runtime")
                .build();

        assertEquals("edge", c.name());
        assertEquals(5, c.startupTimeoutSec());
        assertEquals(3, c.shutdownTimeoutSec());
        assertEquals("h2", c.persistenceMode());
        assertEquals("./data/other", c.h2DbPath());
        assertEquals("edge runtime", c.description());
    }

    @Test
    void nameMustNotBeNull() {
        assertThrows(NullPointerException.class, () ->
                AraRuntimeConfig.builder().name(null).build());
    }

    @Test
    void timeoutsMustBeStrictlyPositive() {
        assertThrows(IllegalArgumentException.class, () ->
                AraRuntimeConfig.builder().startupTimeoutSec(0).build());
        assertThrows(IllegalArgumentException.class, () ->
                AraRuntimeConfig.builder().startupTimeoutSec(-1).build());
        assertThrows(IllegalArgumentException.class, () ->
                AraRuntimeConfig.builder().shutdownTimeoutSec(0).build());
    }

    @Test
    void fromYaml_loadsFixtureAndOverridesDefaults() {
        AraRuntimeConfig c = AraRuntimeConfig.fromYaml();

        assertEquals("my-ara", c.name());
        assertEquals(15, c.startupTimeoutSec());
        assertEquals(7, c.shutdownTimeoutSec());
        assertEquals("test fixture runtime", c.description());
        assertEquals("memory", c.persistenceMode());
        assertEquals("./data/ara", c.h2DbPath(),
                "default h2DbPath equals the fixture value — asserted for the round-trip, not the default");
    }

    @Test
    void fromMap_suppliesDefaultsForMissingKeys() {
        AraRuntimeConfig c = AraRuntimeConfig.fromMap(Map.of());

        assertEquals(AraRuntimeConfig.DEFAULT_NAME, c.name());
        assertEquals(AraRuntimeConfig.DEFAULT_STARTUP_TIMEOUT, c.startupTimeoutSec());
        assertEquals(AraRuntimeConfig.DEFAULT_SHUTDOWN_TIMEOUT, c.shutdownTimeoutSec());
    }

    @Test
    void fromMap_blankEnvironmentStyleValues_fallBackToDefaults() {
        AraRuntimeConfig c = AraRuntimeConfig.fromMap(Map.of(
                "ara.runtime.name", "",
                "ara.runtime.startupTimeoutSec", "  "));

        assertEquals(AraRuntimeConfig.DEFAULT_NAME, c.name());
        assertEquals(AraRuntimeConfig.DEFAULT_STARTUP_TIMEOUT, c.startupTimeoutSec());
    }

    @Test
    void fromMap_nonNumericTimeout_throwsWithTheOffendingKey() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                AraRuntimeConfig.fromMap(Map.of("ara.runtime.startupTimeoutSec", "not-a-number")));
        assertTrue(ex.getMessage().contains("ara.runtime.startupTimeoutSec"),
                () -> "message should name the bad key: " + ex.getMessage());
    }
}