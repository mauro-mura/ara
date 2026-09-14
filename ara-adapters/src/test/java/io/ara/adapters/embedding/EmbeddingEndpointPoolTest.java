package io.ara.adapters.embedding;

import java.util.List;

import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EmbeddingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link EmbeddingEndpointPool}'s ordered failover — the same contract as
 * {@code io.ara.runtime.factory.FailoverLlmClient}'s {@code complete()} cases, minus streaming
 * (embeddings have no streaming call) — plus the dimension-consistency check.
 */
class EmbeddingEndpointPoolTest {

    private static EmbeddingClient client(String id, int dimensions, List<Float> vector) {
        return new EmbeddingClient() {
            @Override public List<Float> embed(String text) { return vector; }
            @Override public int dimensions() { return dimensions; }
            @Override public String providerId() { return id; }
        };
    }

    private static EmbeddingClient failingClient(String id, int dimensions, RuntimeException failure) {
        return new EmbeddingClient() {
            @Override public List<Float> embed(String text) { throw failure; }
            @Override public int dimensions() { return dimensions; }
            @Override public String providerId() { return id; }
        };
    }

    @Test
    void embed_returnsPrimaryResult_whenPrimarySucceeds() {
        EmbeddingClient primary   = client("p", 3, List.of(1f, 2f, 3f));
        EmbeddingClient secondary = failingClient("s", 3, new RuntimeException("must not be used"));

        EmbeddingEndpointPool pool = new EmbeddingEndpointPool(List.of(primary, secondary));
        List<Float> result = pool.embed("hi");

        assertEquals(List.of(1f, 2f, 3f), result);
        assertEquals("p", pool.lastUsedEndpoint());
    }

    @Test
    void embed_failsOverToNextEndpoint_onConnectionError() {
        EmbeddingClient primary = failingClient("p", 3,
                EmbeddingException.connectionError("p", "HTTP connect timed out", null));
        EmbeddingClient secondary = client("s", 3, List.of(4f, 5f, 6f));

        EmbeddingEndpointPool pool = new EmbeddingEndpointPool(List.of(primary, secondary));
        List<Float> result = pool.embed("hi");

        assertEquals(List.of(4f, 5f, 6f), result);
        assertEquals("s", pool.lastUsedEndpoint());
    }

    @Test
    void embed_failsOverToNextEndpoint_onGenericRuntimeException() {
        EmbeddingClient primary   = failingClient("p", 3, new RuntimeException("boom"));
        EmbeddingClient secondary = client("s", 3, List.of(4f, 5f, 6f));

        EmbeddingEndpointPool pool = new EmbeddingEndpointPool(List.of(primary, secondary));

        assertEquals(List.of(4f, 5f, 6f), pool.embed("hi"));
    }

    @Test
    void embed_abortsImmediately_onNonFailoverError() {
        EmbeddingClient primary   = failingClient("p", 3,
                EmbeddingException.authenticationError("p", "invalid api key"));
        EmbeddingClient secondary = client("s", 3, List.of(4f, 5f, 6f));

        EmbeddingEndpointPool pool = new EmbeddingEndpointPool(List.of(primary, secondary));

        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> pool.embed("hi"));
        assertEquals("p", ex.provider());
        assertFalse(ex.shouldFailover());
    }

    @Test
    void embed_throwsLastFailure_whenEveryEndpointFails() {
        EmbeddingClient primary   = failingClient("p", 3, EmbeddingException.networkError("p", "down", null));
        EmbeddingClient secondary = failingClient("s", 3, EmbeddingException.networkError("s", "down", null));

        EmbeddingEndpointPool pool = new EmbeddingEndpointPool(List.of(primary, secondary));

        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> pool.embed("hi"));
        assertEquals("s", ex.provider(), "the last candidate tried is the one whose failure surfaces");
    }

    @Test
    void dimensions_delegatesToThePool() {
        EmbeddingEndpointPool pool = new EmbeddingEndpointPool(
                List.of(client("p", 1536, List.of()), client("s", 1536, List.of())));

        assertEquals(1536, pool.dimensions());
    }

    @Test
    void constructor_rejectsMismatchedDimensions() {
        EmbeddingClient primary   = client("p", 1536, List.of());
        EmbeddingClient secondary = client("s", 768, List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingEndpointPool(List.of(primary, secondary)));
        assertTrue(ex.getMessage().contains("1536"), ex.getMessage());
        assertTrue(ex.getMessage().contains("768"), ex.getMessage());
    }

    @Test
    void constructor_rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingEndpointPool(List.of()));
    }

    @Test
    void providerId_namesEveryEndpointInOrder() {
        EmbeddingEndpointPool pool = new EmbeddingEndpointPool(
                List.of(client("openai-x", 3, List.of()), client("openai-x@https://fallback", 3, List.of())));

        assertEquals("failover[openai-x→openai-x@https://fallback]", pool.providerId());
    }
}
