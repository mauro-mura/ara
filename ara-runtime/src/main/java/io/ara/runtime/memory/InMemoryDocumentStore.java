package io.ara.runtime.memory;

import io.ara.core.memory.EmbeddingClient;
import io.ara.core.retriever.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pure in-memory document store for RAG — no external infrastructure required.
 *
 * <p>Vectors are stored in a Java list and searched with brute-force cosine
 * similarity (O(n·d)). Suitable for development, testing, and small knowledge
 * bases (up to ~10 k chunks). For production use {@link DocumentStore} (Qdrant).
 *
 * <p>All state is lost when the JVM stops — documents are re-indexed from H2
 * on startup via {@code KnowledgeBaseService#init()}.
 */
public final class InMemoryDocumentStore implements KbStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDocumentStore.class);

    private record Entry(String docId, String title, String content, float[] vector) {}

    private final String          kbId;
    private final EmbeddingClient embeddingClient;
    private final List<Entry>     entries = new CopyOnWriteArrayList<>();

    /** In-memory registry of indexed documents: docId → title. */
    private final Map<String, String> docRegistry = new LinkedHashMap<>();

    public InMemoryDocumentStore(String kbId, EmbeddingClient embeddingClient) {
        this.kbId            = Objects.requireNonNull(kbId,            "kbId must not be null");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
    }

    // ── No-op lifecycle ───────────────────────────────────────────────────────

    /** No-op: no external collection to create. */
    public void ensureCollection() {
        log.info("[InMemoryDocumentStore] kb=[{}] ready (in-memory)", kbId);
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public int indexDocument(String docId, String title, String content) {
        Objects.requireNonNull(docId,   "docId must not be null");
        Objects.requireNonNull(title,   "title must not be null");
        Objects.requireNonNull(content, "content must not be null");

        List<String> chunks = DocumentStore.chunk(content);
        log.info("[InMemoryDocumentStore] Indexing '{}' → {} chunks", title, chunks.size());

        for (String chunkText : chunks) {
            List<Float> vector = embeddingClient.embed(chunkText);
            entries.add(new Entry(docId, title, chunkText, toFloatArray(vector)));
        }
        synchronized (docRegistry) { docRegistry.put(docId, title); }
        log.info("[InMemoryDocumentStore] Indexed '{}' ({} chunks)", title, chunks.size());
        return chunks.size();
    }

    public boolean deleteDocument(String docId) {
        Objects.requireNonNull(docId, "docId must not be null");
        entries.removeIf(e -> e.docId().equals(docId));
        boolean known;
        synchronized (docRegistry) { known = docRegistry.remove(docId) != null; }
        log.info("[InMemoryDocumentStore] Deleted document '{}'", docId);
        return known;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<DocumentChunk> search(String query, int maxResults) {
        if (query == null || query.isBlank()) return List.of();

        float[] qVec = toFloatArray(embeddingClient.embed(query));

        record Scored(Entry entry, float score) {}
        List<Scored> scored = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            scored.add(new Scored(e, cosine(qVec, e.vector())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        return scored.stream()
                .limit(maxResults)
                .map(s -> new DocumentChunk(
                        s.entry().docId(),
                        s.entry().title(),
                        s.entry().content(),
                        0,
                        s.score()))
                .toList();
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int maxResults) {
        return search(query, maxResults).stream()
                .map(c -> new RetrievedChunk(c.docId(), c.title(), c.content(), c.score()))
                .toList();
    }

    public Map<String, String> listDocuments() {
        synchronized (docRegistry) { return Collections.unmodifiableMap(new LinkedHashMap<>(docRegistry)); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float cosine(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0f;
        return dot / ((float) Math.sqrt(na) * (float) Math.sqrt(nb));
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }
}
