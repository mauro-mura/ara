package io.ara.core.media;

/**
 * Turns a {@link MediaRef} back into its bytes — the "last mile" of the media path, called
 * once per media item inside the adapter, at the moment the provider request is built.
 *
 * <p>Read-only on purpose: it is narrower than the {@link MediaStore} it usually reads
 * from, so an adapter cannot store or delete anything, and it also centralises the one
 * error message that matters here — a reference whose payload is missing must say
 * <em>which</em> reference, since by that point the caller is several layers away.
 *
 * <p>Never {@code null} on {@code LlmCallContext}: when no store is wired,
 * {@link #none()} still answers, by failing with that message. That is deliberate — a
 * resolver that returned empty bytes would send the model an empty document and let it
 * answer confidently about nothing.
 */
@FunctionalInterface
public interface MediaResolver {

    /**
     * Returns the bytes of {@code ref}.
     *
     * <p>Only called for media ARA holds itself. A {@link MediaRef#isExternal()} reference
     * is passed to the provider as a URI and never reaches here.
     *
     * @throws IllegalStateException if no payload is available under {@code ref.mediaId()}
     */
    byte[] bytesOf(MediaRef ref);

    /** A resolver reading from {@code store}, failing with the ref's name and id if absent. */
    static MediaResolver backedBy(MediaStore store) {
        return ref -> store.get(ref.mediaId()).orElseThrow(() -> new IllegalStateException(
                "No bytes available for media '" + ref.name() + "' (mediaId=" + ref.mediaId()
                        + "). Either it was never stored, or no MediaStore is wired — see "
                        + "AraRuntime.builder().mediaStore(...)."));
    }

    /**
     * The default: resolves nothing, so every stored reference fails naming itself.
     *
     * <p>A single shared instance, not a fresh lambda per call, so that "is this still the
     * default?" is answerable by identity — which is how {@code LlmCallContext} distinguishes
     * a resolver a caller set deliberately from the one it fell back to.
     */
    static MediaResolver none() {
        return NoMediaResolver.INSTANCE;
    }
}
