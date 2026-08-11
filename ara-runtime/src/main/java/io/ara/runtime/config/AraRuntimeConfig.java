package io.ara.runtime.config;

import io.ara.runtime.AraRuntime;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for {@link AraRuntime}.
 *
 * <p>Constructed either programmatically via {@link Builder} or loaded from
 * {@code ara.yml} via {@link #fromYaml()}.
 *
 * <p>Supported {@code ara.yml} keys:
 * <pre>
 * ara:
 *   runtime:
 *     name: my-ara
 *     startupTimeoutSec: 30
 *     shutdownTimeoutSec: 10
 *     description: ""
 *   persistence:
 *     mode: memory             # memory | h2
 *     h2DbPath: ./data/ara
 * </pre>
 *
 * <p>Environment variable substitution is supported in all values:
 * {@code ${ENV_VAR}} and {@code ${ENV_VAR:default}}.
 */
public record AraRuntimeConfig(
        String name,
        int startupTimeoutSec,
        int shutdownTimeoutSec,
        String persistenceMode,
        String h2DbPath,
        String description
) {

    public AraRuntimeConfig {
        Objects.requireNonNull(name, "name must not be null");
        if (startupTimeoutSec  <= 0) throw new IllegalArgumentException("startupTimeoutSec must be > 0");
        if (shutdownTimeoutSec <= 0) throw new IllegalArgumentException("shutdownTimeoutSec must be > 0");
    }

    // ── defaults ──────────────────────────────────────────────────────────────

    static final String DEFAULT_NAME              = "ara-runtime";
    static final int    DEFAULT_STARTUP_TIMEOUT   = 30;
    static final int    DEFAULT_SHUTDOWN_TIMEOUT  = 10;
    static final String DEFAULT_PERSISTENCE_MODE  = "memory";
    static final String DEFAULT_H2_DB_PATH        = "./data/ara";

    /** Returns an instance populated entirely from defaults — useful for tests. */
    public static AraRuntimeConfig defaults() {
        return new AraRuntimeConfig(
                DEFAULT_NAME, DEFAULT_STARTUP_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT,
                DEFAULT_PERSISTENCE_MODE, DEFAULT_H2_DB_PATH, "");
    }

    // ── YAML factory ──────────────────────────────────────────────────────────

    /**
     * Loads {@code ara.yml} from the default search path (CWD, then classpath)
     * and merges it with defaults.
     */
    public static AraRuntimeConfig fromYaml() {
        return fromMap(AraYamlLoader.load());
    }

    /**
     * Loads the file at {@code path} and merges it with defaults.
     *
     * @param path absolute or relative path to an {@code ara.yml} file
     */
    public static AraRuntimeConfig fromYaml(java.nio.file.Path path) {
        return fromMap(AraYamlLoader.loadFromPath(path));
    }

    static AraRuntimeConfig fromMap(Map<String, String> m) {
        return new AraRuntimeConfig(
                get(m, "ara.runtime.name",                  DEFAULT_NAME),
                getInt(m, "ara.runtime.startupTimeoutSec",  DEFAULT_STARTUP_TIMEOUT),
                getInt(m, "ara.runtime.shutdownTimeoutSec", DEFAULT_SHUTDOWN_TIMEOUT),
                get(m, "ara.persistence.mode",              DEFAULT_PERSISTENCE_MODE),
                get(m, "ara.persistence.h2DbPath",          DEFAULT_H2_DB_PATH),
                get(m, "ara.runtime.description",           "")
        );
    }

    // ── programmatic builder ──────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name             = DEFAULT_NAME;
        private int    startupTimeout   = DEFAULT_STARTUP_TIMEOUT;
        private int    shutdownTimeout  = DEFAULT_SHUTDOWN_TIMEOUT;
        private String persistenceMode  = DEFAULT_PERSISTENCE_MODE;
        private String h2DbPath         = DEFAULT_H2_DB_PATH;
        private String description      = "";

        private Builder() {}

        public Builder name(String name)                     { this.name = name;            return this; }
        public Builder startupTimeoutSec(int v)              { this.startupTimeout = v;     return this; }
        public Builder shutdownTimeoutSec(int v)             { this.shutdownTimeout = v;    return this; }
        public Builder persistenceMode(String v)             { this.persistenceMode = v;    return this; }
        public Builder h2DbPath(String v)                    { this.h2DbPath = v;           return this; }
        public Builder description(String v)                 { this.description = v;        return this; }

        public AraRuntimeConfig build() {
            return new AraRuntimeConfig(name, startupTimeout, shutdownTimeout,
                    persistenceMode, h2DbPath, description);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String get(Map<String, String> m, String key, String def) {
        String v = m.get(key);
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static int getInt(Map<String, String> m, String key, int def) {
        String v = m.get(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for key '" + key + "': " + v);
        }
    }

}
