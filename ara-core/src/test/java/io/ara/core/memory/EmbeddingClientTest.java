package io.ara.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link EmbeddingClient#embedAll(List)}'s default: a sequential loop over
 * {@link EmbeddingClient#embed(String)}, in input order, so every implementation that predates
 * this method (demo clients, test doubles) keeps compiling and behaving correctly without an
 * override — see the method javadoc.
 */
class EmbeddingClientTest {

    /** Deterministic 1-element vector derived from the text's length, so order is checkable. */
    private static final class CountingClient implements EmbeddingClient {
        final AtomicInteger embedCalls = new AtomicInteger();

        @Override
        public List<Float> embed(String text) {
            embedCalls.incrementAndGet();
            return List.of((float) text.length());
        }

        @Override
        public int dimensions() { return 1; }
    }

    @Test
    void default_embedAll_loops_over_embed_in_order() {
        CountingClient client = new CountingClient();

        List<List<Float>> vectors = client.embedAll(List.of("a", "bb", "ccc"));

        assertEquals(3, client.embedCalls.get(), "the default must call embed() once per input");
        assertEquals(List.of(1.0f), vectors.get(0));
        assertEquals(List.of(2.0f), vectors.get(1));
        assertEquals(List.of(3.0f), vectors.get(2));
    }

    @Test
    void default_embedAll_on_an_empty_list_calls_embed_zero_times() {
        CountingClient client = new CountingClient();

        List<List<Float>> vectors = client.embedAll(List.of());

        assertEquals(0, client.embedCalls.get());
        assertEquals(0, vectors.size());
    }
}
