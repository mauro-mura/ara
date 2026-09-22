package io.ara.examples.support;

import io.ara.core.memory.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, dependency-free embedding used by the offline demos (RAG, working memory,
 * failover): hash bag-of-words projected onto a fixed-size vector, then L2-normalised.
 *
 * <p>Good enough to make cosine similarity work in a demo — NOT fit for production (use a
 * real {@link EmbeddingClient}: OpenAI, Cohere, a local sentence-embedding model, ...). All
 * of those are interchangeable behind the same interface, which is exactly what the failover
 * example demonstrates by putting its endpoint pool in front of this class.
 *
 * <p>The {@code verbose} constructor is for the failover story: it prints a line per embed so
 * the output shows which endpoint actually served each vector. The RAG and working-memory
 * examples use the silent form.
 */
public final class DemoEmbeddingClient implements EmbeddingClient {

    private static final int DIM = 64;

    private final String  providerId;
    private final boolean verbose;

    /** Silent stand-in named after the tool that provides it. */
    public DemoEmbeddingClient() {
        this("demo-embeddings", false);
    }

    /**
     * @param providerId reported by {@link #providerId()}
     * @param verbose    trace each {@link #embed(String)} call to stdout
     */
    public DemoEmbeddingClient(String providerId, boolean verbose) {
        this.providerId = providerId;
        this.verbose = verbose;
    }

    @Override
    public List<Float> embed(String text) {
        if (verbose) System.out.printf("  [%s] embed -> answering%n", providerId);
        float[] v = new float[DIM];
        for (String token : text.toLowerCase().split("\\W+")) {
            if (token.isBlank()) continue;
            v[Math.floorMod(token.hashCode(), DIM)] += 1f;
        }
        float norm = 0f;
        for (float f : v) norm += f * f;
        norm = (float) Math.sqrt(norm);
        List<Float> out = new ArrayList<>(DIM);
        for (float f : v) out.add(norm > 0 ? f / norm : 0f);
        return out;
    }

    @Override
    public int dimensions() {
        return DIM;
    }

    @Override
    public String providerId() {
        return providerId;
    }
}
