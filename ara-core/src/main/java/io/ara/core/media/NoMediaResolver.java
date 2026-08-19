package io.ara.core.media;

/**
 * The {@link MediaResolver#none()} singleton: resolves nothing, and says so by failing with
 * the reference's own name and id.
 *
 * <p>A real class rather than a lambda so the default is one identifiable instance — see
 * {@link MediaResolver#none()}.
 */
final class NoMediaResolver implements MediaResolver {

    static final NoMediaResolver INSTANCE = new NoMediaResolver();

    private NoMediaResolver() {}

    @Override
    public byte[] bytesOf(MediaRef ref) {
        throw new IllegalStateException(
                "No bytes available for media '" + ref.name() + "' (mediaId=" + ref.mediaId()
                        + "): no MediaStore is wired — see AraRuntime.builder().mediaStore(...).");
    }
}
