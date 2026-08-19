package io.ara.core.media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Where the bytes of task media live, keyed by the SHA-256 digest of their content.
 *
 * <p>Owned by the runtime (wired via {@code AraRuntime.Builder}) rather than configured
 * per agent, for the same reason as {@code SessionStore}: <em>where</em> bytes are stored
 * is an infrastructure decision, not something one agent should answer differently from
 * the agent it delegates to.
 *
 * <h2>Content-addressed, not generated ids</h2>
 * <p>{@link #put} does not accept or invent an id — it derives one from the bytes. Two
 * puts of the same document therefore return the same {@link MediaRef#mediaId()} and store
 * the payload once, so deduplication is a property of the <em>content</em>: the same PDF
 * submitted by two unrelated tasks costs one entry, not two. With generated ids (UUIDs)
 * dedup would only hold when the caller happened to reuse the same {@code MediaRef}
 * object — which is exactly the case that needs it least. The price is one SHA-256 pass
 * per {@code put}, negligible next to the base64 encoding the adapter performs anyway.
 *
 * <p>The immutable, content-derived id is also what makes cleanup tractable: a real
 * backend keeps a count per digest and drops the payload when it reaches zero, with no
 * version reconciliation and no risk of deleting bytes another session still cites.
 * {@link #delete} is that low-level operation; <em>who</em> calls it and <em>when</em> is
 * the concern of a production store, along with TTLs for media that never entered a
 * session and per-user quotas. The interface stays at {@code put}/{@code get}/{@code
 * delete} so as not to prejudge any of those. A {@code uri}-backed {@link MediaRef} is
 * never the store's to delete — those bytes belong to someone else.
 *
 * <p>Always present, never {@code null} — the same null-object idiom as
 * {@code SessionStore.noop()} and {@code RunState.noop()}: {@link #noop()} is the default,
 * so no caller has to branch on "is a store configured?".
 */
public interface MediaStore {

    /**
     * Stores {@code bytes} and returns a reference to them. The returned
     * {@link MediaRef#mediaId()} is {@link #digestOf(byte[])} of the content, so calling
     * this twice with identical bytes yields the same id and stores the payload once.
     *
     * @throws IllegalArgumentException if {@code mimeType} is not in {@link MediaTypes#allowed()}
     * @throws UnsupportedOperationException if this store does not accept writes (see {@link #noop()})
     */
    MediaRef put(String name, String mimeType, byte[] bytes);

    /** Returns the bytes stored under {@code mediaId}, or empty if this store has none. */
    Optional<byte[]> get(String mediaId);

    /**
     * Removes the payload stored under {@code mediaId}. A no-op when nothing is stored
     * under it — deleting twice is not an error.
     */
    void delete(String mediaId);

    /**
     * The SHA-256 digest of {@code bytes} as lowercase hex — the id every implementation
     * must assign in {@link #put}.
     *
     * <p>Part of the interface, not of one implementation, because ids are only
     * interchangeable between stores if they are all derived the same way: a store that
     * hashed differently would silently break dedup for anything that crossed it.
     */
    static String digestOf(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256 (it is mandated by the platform spec), so this is
            // unreachable — declared only because MessageDigest cannot say so in its type.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    /**
     * A process-local, in-memory reference implementation — not durable across a JVM
     * restart, and it never evicts. Useful for tests and single-process runs; a deployment
     * that keeps media around needs a real backend.
     */
    static MediaStore inMemory() {
        return new InMemoryMediaStore();
    }

    /**
     * Rejects every write and reads empty — the default, for the many deployments that
     * never attach media.
     *
     * <p>{@link #put} throws rather than silently discarding: a caller storing a document
     * expects to be able to retrieve it, and returning a reference to bytes that were never
     * kept only moves the failure to the adapter, far from the code that caused it. Reads
     * stay empty so that {@code get} needs no special case.
     */
    static MediaStore noop() {
        return NoopMediaStore.INSTANCE;
    }
}
