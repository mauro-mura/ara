package io.ara.core.media;

import io.ara.core.media.MediaTypes.MediaKind;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class MediaRefTest {

    @Test
    void unsupported_mime_fails_at_construction() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MediaRef("abc", "audio/mpeg", "song.mp3", 10, null));
        assertTrue(ex.getMessage().contains("audio/mpeg"), ex.getMessage());
    }

    @Test
    void mime_is_stored_in_canonical_form() {
        assertEquals("application/pdf",
                new MediaRef("abc", "  APPLICATION/PDF ", "contract.pdf", 10, null).mimeType());
    }

    @Test
    void blank_id_and_name_are_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRef(" ", "image/png", "shot.png", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRef("abc", "image/png", " ", 1, null));
    }

    @Test
    void negative_size_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRef("abc", "image/png", "shot.png", -1, null));
    }

    @Test
    void kind_is_derived_from_the_mime_type() {
        assertEquals(MediaKind.DOCUMENT,
                new MediaRef("abc", "application/pdf", "contract.pdf", 10, null).kind());
    }

    @Test
    void remote_reference_is_external_and_has_unknown_size() {
        MediaRef ref = MediaRef.remote(URI.create("https://example.test/a.pdf"), "application/pdf", "a.pdf");
        assertTrue(ref.isExternal());
        assertEquals(0L, ref.sizeBytes());
        assertEquals("https://example.test/a.pdf", ref.mediaId());
    }

    @Test
    void stored_reference_is_not_external() {
        assertFalse(new MediaRef("abc", "image/png", "shot.png", 3, null).isExternal());
    }
}
