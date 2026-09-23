package io.ara.runtime.memory;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for the Qdrant vector store connection.
 *
 * <table border="1">
 *   <caption>Supported environment variables</caption>
 *   <tr><th>Variable</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>QDRANT_HOST</td><td>localhost</td><td>Qdrant REST host</td></tr>
 *   <tr><td>QDRANT_PORT</td><td>6333</td><td>Qdrant REST port</td></tr>
 *   <tr><td>QDRANT_API_KEY</td><td>–</td><td>Required for Qdrant Cloud</td></tr>
 *   <tr><td>QDRANT_COLLECTION</td><td>ara_semantic_memory</td><td>Collection name</td></tr>
 * </table>
 *
 * @param host           Qdrant REST host
 * @param port           Qdrant REST port (default 6333)
 * @param apiKey         optional API key (null for local deployments)
 * @param collectionName the collection that stores all ARA semantic entries
 * @param vectorSize     must match the dimension of the {@link io.ara.core.memory.EmbeddingClient}
 * @param requestTimeout per-request HTTP timeout for {@link QdrantSemanticStore}/{@link
 *                       DocumentStore} (P8/U21, 2026-09-23); {@code null} keeps each
 *                       class's own historic hardcoded default (15s / 20s respectively —
 *                       see their own {@code send()} javadoc) rather than imposing one
 *                       shared value across two classes whose prior defaults differed.
 */
public record QdrantConfig(
        String   host,
        int      port,
        String   apiKey,
        String   collectionName,
        int      vectorSize,
        Duration requestTimeout
) {

    public QdrantConfig {
        Objects.requireNonNull(host,           "host must not be null");
        Objects.requireNonNull(collectionName, "collectionName must not be null");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        if (vectorSize < 1)           throw new IllegalArgumentException("vectorSize must be >= 1");
        if (requestTimeout != null && (requestTimeout.isZero() || requestTimeout.isNegative())) {
            throw new IllegalArgumentException("requestTimeout must be positive when set");
        }
    }

    /** Backward-compatible constructor — {@code requestTimeout} defaults to {@code null} (each caller's own historic default). */
    public QdrantConfig(String host, int port, String apiKey, String collectionName, int vectorSize) {
        this(host, port, apiKey, collectionName, vectorSize, null);
    }

    /** Base URL for Qdrant REST API calls. */
    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    /**
     * Reads configuration from environment variables, falling back to defaults.
     *
     * @param vectorSize must match the configured {@link io.ara.core.memory.EmbeddingClient}
     */
    public static QdrantConfig fromEnv(int vectorSize) {
        return new QdrantConfig(
                env("QDRANT_HOST",       "192.168.1.95"),
                Integer.parseInt(env("QDRANT_PORT", "6333")),
                System.getenv("QDRANT_API_KEY"),
                env("QDRANT_COLLECTION", "ara_semantic_memory"),
                vectorSize
        );
    }

    /** Convenience factory for a local Qdrant instance with default settings. */
    public static QdrantConfig local(int vectorSize) {
        return new QdrantConfig("localhost", 6333, null, "ara_semantic_memory", vectorSize);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : def;
    }
}