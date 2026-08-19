package io.ara.core.media;

import java.util.Optional;

/** No-op {@link MediaStore}: refuses writes, always reads empty. The default. */
final class NoopMediaStore implements MediaStore {

    static final NoopMediaStore INSTANCE = new NoopMediaStore();

    private NoopMediaStore() {}

    @Override
    public MediaRef put(String name, String mimeType, byte[] bytes) {
        throw new UnsupportedOperationException(
                "No MediaStore is configured — cannot store '" + name + "'. "
                        + "Wire one with AraRuntime.builder().mediaStore(MediaStore.inMemory()).");
    }

    @Override
    public Optional<byte[]> get(String mediaId) {
        return Optional.empty();
    }

    @Override
    public void delete(String mediaId) {
        // nothing is ever stored
    }
}
