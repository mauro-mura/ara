package io.ara.core.media;

import io.ara.core.media.MediaTypes.MediaKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the allowlist and the classification really are one table: every accepted
 * MIME classifies, and nothing outside the table is accepted anywhere.
 */
class MediaTypesTest {

    @Test
    void every_allowed_type_has_a_kind() {
        for (String mimeType : MediaTypes.allowed()) {
            assertNotNull(MediaTypes.kindOf(mimeType), mimeType + " must classify");
        }
    }

    @Test
    void kinds_match_the_documented_categories() {
        assertEquals(MediaKind.IMAGE,    MediaTypes.kindOf("image/png"));
        assertEquals(MediaKind.IMAGE,    MediaTypes.kindOf("image/jpeg"));
        assertEquals(MediaKind.IMAGE,    MediaTypes.kindOf("image/webp"));
        assertEquals(MediaKind.DOCUMENT, MediaTypes.kindOf("application/pdf"));
        assertEquals(MediaKind.TEXT,     MediaTypes.kindOf("text/plain"));
        assertEquals(MediaKind.TEXT,     MediaTypes.kindOf("text/markdown"));
        assertEquals(MediaKind.TEXT,     MediaTypes.kindOf("text/csv"));
    }

    @Test
    void normalized_trims_and_lowercases() {
        assertEquals("image/png", MediaTypes.normalized("  IMAGE/PNG  "));
    }

    @Test
    void unsupported_type_is_rejected_by_both_entry_points() {
        assertThrows(IllegalArgumentException.class, () -> MediaTypes.normalized("audio/mpeg"));
        assertThrows(IllegalArgumentException.class, () -> MediaTypes.kindOf("audio/mpeg"));
    }

    @Test
    void mime_parameters_are_not_accepted() {
        assertThrows(IllegalArgumentException.class,
                () -> MediaTypes.normalized("text/plain; charset=utf-8"));
    }

    @Test
    void allowed_set_is_immutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> MediaTypes.allowed().add("audio/mpeg"));
    }
}
