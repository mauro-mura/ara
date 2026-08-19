package io.ara.core.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MediaStoreTest {

    private static final byte[] PDF = "%PDF-1.4 contract".getBytes(StandardCharsets.UTF_8);

    @Test
    void put_derives_the_id_from_the_content() {
        MediaStore store = MediaStore.inMemory();
        assertEquals(MediaStore.digestOf(PDF), store.put("a.pdf", "application/pdf", PDF).mediaId());
    }

    @Test
    void identical_bytes_dedup_by_content_not_by_reference() {
        MediaStore store = MediaStore.inMemory();
        MediaRef first  = store.put("contract.pdf", "application/pdf", PDF);
        MediaRef second = store.put("copia-contratto.pdf", "application/pdf", PDF.clone());

        assertEquals(first.mediaId(), second.mediaId());
        // One payload, addressed by both references: deleting the shared id empties both.
        store.delete(first.mediaId());
        assertTrue(store.get(second.mediaId()).isEmpty());
    }

    @Test
    void different_bytes_get_different_ids() {
        MediaStore store = MediaStore.inMemory();
        MediaRef a = store.put("a.pdf", "application/pdf", PDF);
        MediaRef b = store.put("b.pdf", "application/pdf", "other".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(a.mediaId(), b.mediaId());
    }

    @Test
    void get_returns_the_stored_bytes() {
        MediaStore store = MediaStore.inMemory();
        MediaRef ref = store.put("a.pdf", "application/pdf", PDF);
        assertArrayEquals(PDF, store.get(ref.mediaId()).orElseThrow());
    }

    @Test
    void stored_bytes_are_isolated_from_caller_mutation() {
        MediaStore store = MediaStore.inMemory();
        byte[] mutable = PDF.clone();
        MediaRef ref = store.put("a.pdf", "application/pdf", mutable);

        mutable[0] = 'X';
        assertArrayEquals(PDF, store.get(ref.mediaId()).orElseThrow(),
                "a caller mutating its array must not change a payload keyed by the original digest");
    }

    @Test
    void get_of_unknown_id_is_empty_and_delete_is_idempotent() {
        MediaStore store = MediaStore.inMemory();
        assertTrue(store.get("nope").isEmpty());
        assertDoesNotThrow(() -> store.delete("nope"));
    }

    @Test
    void unsupported_mime_is_rejected_by_put() {
        MediaStore store = MediaStore.inMemory();
        assertThrows(IllegalArgumentException.class, () -> store.put("s.mp3", "audio/mpeg", PDF));
    }

    @Test
    void noop_store_refuses_writes_and_reads_empty() {
        MediaStore store = MediaStore.noop();
        assertThrows(UnsupportedOperationException.class, () -> store.put("a.pdf", "application/pdf", PDF));
        assertTrue(store.get("anything").isEmpty());
        assertDoesNotThrow(() -> store.delete("anything"));
    }
}
