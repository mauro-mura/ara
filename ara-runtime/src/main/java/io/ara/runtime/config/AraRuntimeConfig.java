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
 *   circuitBreaker:
 *     failureThreshold: 3      # consecutive failover-able failures that open an endpoint's circuit
 *     cooldownSec: 30          # how long an open circuit skips the endpoint before one trial call
 * </pre>
 *
 * <p>Environment variable substitution is supported in all values:
 * {@code ${ENV_VAR}} and {@code ${ENV_VAR:default}}.
 *
 * <p>Field contract — each field is either <em>consumed</em> by the runtime or
 * explicitly <em>reserved</em>; nothing is dead config. Consumed today: {@link
 * #name()} (runtime identity, logs and lifecycle messages), {@link
 * #description()} (identity, included in the startup log), {@link
 * #startupTimeoutSec()} (bounds {@code AraRuntime.start()}'s provider-driven
 * agent creation), {@link #shutdownTimeoutSec()} (executor drain before {@code
 * shutdownNow()}), {@link #circuitFailureThreshold()} and {@link
 * #circuitCooldownSec()} (the per-endpoint LLM circuit breaker's health policy,
 * applied by {@code AgentFactory} to the registry every session shares — see
 * {@code CircuitBreakerLlmClient}). Reserved: {@link #persistenceMode()} and {@link #h2DbPath()}
 * anticipate an H2-backed persistence layer that does not exist yet — they are
 * parsed, validated and round-tripped but intentionally not consumed, and must
 * not be wired into behavior prematurely (previously the two most confounding
 * "configuration ghost" fields; see {@code AraRuntime} package README §8).
 */
public record AraRuntimeConfig(
        String name,
        int startupTimeoutSec,
        int shutdownTimeoutSec,
        String persistenceMode,
        String h2DbPath,
        String description,
        int circuitFailureThreshold,
        int circuitCooldownSec
) {

    public AraRuntimeConfig {
        Objects.requireNonNull(name, "name must not be null");
        if (startupTimeoutSec  <= 0) throw new IllegalArgumentException("startupTimeoutSec must be > 0");
        if (shutdownTimeoutSec <= 0) throw new IllegalArgumentException("shutdownTimeoutSec must be > 0");
        // >= 1, not > 0, to say what the value means: one failure is the minimum that can open a
        // circuit, and 0 would mean "open before anything failed" — a pool that skips every
        // candidate forever.
        if (circuitFailureThreshold < 1)
            throw new IllegalArgumentException("circuitFailureThreshold must be >= 1");
        if (circuitCooldownSec     <= 0)
            throw new IllegalArgumentException("circuitCooldownSec must be > 0");
    }

    /**
     * The circuit cooldown as the {@link java.time.Duration} the breaker registry takes, so the
     * seconds-to-Duration conversion lives next to the field it belongs to rather than at the
     * wiring call site.
     */
    public java.time.Duration circuitCooldown() {
        return java.time.Duration.ofSeconds(circuitCooldownSec);
    }

    // ── defaults ──────────────────────────────────────────────────────────────

    static final String DEFAULT_NAME              = "ara-runtime";
    static final int    DEFAULT_STARTUP_TIMEOUT   = 30;
    static final int    DEFAULT_SHUTDOWN_TIMEOUT  = 10;
    static final String DEFAULT_PERSISTENCE_MODE  = "memory";
    static final String DEFAULT_H2_DB_PATH        = "./data/ara";
    /**
     * Circuit-breaker defaults, deliberately equal to the values
     * {@code CircuitBreakerLlmClient} hardcoded before they became configurable, so a
     * deployment that sets neither key behaves exactly as it did.
     */
    static final int    DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 3;
    static final int    DEFAULT_CIRCUIT_COOLDOWN_SEC      = 30;

    /** Returns an instance populated entirely from defaults — useful for tests. */
    public static AraRuntimeConfig defaults() {
        return new AraRuntimeConfig(
                DEFAULT_NAME, DEFAULT_STARTUP_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT,
                DEFAULT_PERSISTENCE_MODE, DEFAULT_H2_DB_PATH, "",
                DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_COOLDOWN_SEC);
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
                get(m, "ara.runtime.description",           ""),
                getInt(m, "ara.circuitBreaker.failureThreshold", DEFAULT_CIRCUIT_FAILURE_THRESHOLD),
                getInt(m, "ara.circuitBreaker.cooldownSec",      DEFAULT_CIRCUIT_COOLDOWN_SEC)
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
        private int    circuitFailureThreshold = DEFAULT_CIRCUIT_FAILURE_THRESHOLD;
        private int    circuitCooldownSec      = DEFAULT_CIRCUIT_COOLDOWN_SEC;

        private Builder() {}

        public Builder name(String name)                     { this.name = name;            return this; }
        public Builder startupTimeoutSec(int v)              { this.startupTimeout = v;     return this; }
        public Builder shutdownTimeoutSec(int v)             { this.shutdownTimeout = v;    return this; }
        public Builder persistenceMode(String v)             { this.persistenceMode = v;    return this; }
        public Builder h2DbPath(String v)                    { this.h2DbPath = v;           return this; }
        public Builder description(String v)                 { this.description = v;        return this; }

        /**
         * Consecutive failover-able failures (timeout, connection, 5xx, rate limit) after which an
         * LLM endpoint's circuit opens and the failover pool stops paying its timeout on every
         * call. Default {@value #DEFAULT_CIRCUIT_FAILURE_THRESHOLD}.
         */
        public Builder circuitFailureThreshold(int v)        { this.circuitFailureThreshold = v; return this; }

        /**
         * How long an open circuit skips its endpoint before a single trial call is allowed
         * through. Default {@value #DEFAULT_CIRCUIT_COOLDOWN_SEC}s.
         */
        public Builder circuitCooldownSec(int v)             { this.circuitCooldownSec = v;      return this; }

        public AraRuntimeConfig build() {
            return new AraRuntimeConfig(name, startupTimeout, shutdownTimeout,
                    persistenceMode, h2DbPath, description,
                    circuitFailureThreshold, circuitCooldownSec);
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
