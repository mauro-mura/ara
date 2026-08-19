package io.ara.core.media;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link MediaStore} backed by a map from content digest to bytes.
 *
 * <p>Concurrent because media can be put from any caller thread while a task on another
 * thread reads them; {@code putIfAbsent} makes a repeated put of identical content a
 * no-op rather than a rewrite, which is what content-addressing buys.
 *
 * <p>Stored arrays are copied on the way in and on the way out. Without that, a caller
 * mutating the array it handed over would silently change a payload whose id — the digest
 * of the <em>original</em> bytes — no longer describes it, which is the one invariant the
 * whole content-addressing scheme rests on.
 */
final class InMemoryMediaStore implements MediaStore {

    private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();

    @Override
    public MediaRef put(String name, String mimeType, byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes must not be null");
        String mediaId = MediaStore.digestOf(bytes);
        payloads.putIfAbsent(mediaId, bytes.clone());
        return new MediaRef(mediaId, mimeType, name, bytes.length, null);
    }

    @Override
    public Optional<byte[]> get(String mediaId) {
        return Optional.ofNullable(payloads.get(mediaId)).map(byte[]::clone);
    }

    @Override
    public void delete(String mediaId) {
        payloads.remove(mediaId);
    }
}
