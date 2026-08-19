package io.ara.runtime.contract;

import io.ara.core.media.MediaRef;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MediaLimitsTest {

    private static MediaRef pdf(String name, long bytes) {
        return new MediaRef("digest-" + name, "application/pdf", name, bytes, null);
    }

    @Test
    void a_task_within_the_limits_passes() {
        assertTrue(MediaLimits.of(2, 10_000).validate(List.of(pdf("a.pdf", 4_000))).isEmpty());
    }

    @Test
    void a_text_only_task_always_passes_even_with_zero_limits() {
        assertTrue(MediaLimits.none().validate(List.of()).isEmpty());
    }

    @Test
    void too_many_files_is_rejected_with_both_numbers() {
        var rejection = MediaLimits.of(1, 10_000)
                .validate(List.of(pdf("a.pdf", 10), pdf("b.pdf", 10)));
        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("2"), rejection.get());
        assertTrue(rejection.get().contains("1"), rejection.get());
    }

    @Test
    void the_byte_total_is_summed_across_attachments() {
        assertTrue(MediaLimits.of(5, 1_000)
                .validate(List.of(pdf("a.pdf", 600), pdf("b.pdf", 600)))
                .isPresent(), "600 + 600 exceeds 1000 even though neither file does alone");
    }

    @Test
    void a_narrower_accepted_set_rejects_a_globally_valid_type() {
        var rejection = MediaLimits.of(5, 10_000, Set.of("image/png"))
                .validate(List.of(pdf("contract.pdf", 10)));
        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("application/pdf"), rejection.get());
    }

    @Test
    void none_rejects_any_attachment() {
        assertTrue(MediaLimits.none().validate(List.of(pdf("a.pdf", 1))).isPresent());
    }

    @Test
    void a_type_outside_the_global_allowlist_is_a_configuration_error() {
        assertThrows(IllegalArgumentException.class,
                () -> MediaLimits.of(1, 10, Set.of("audio/mpeg")));
    }

    @Test
    void an_external_reference_of_unknown_size_counts_as_a_file_but_not_as_bytes() {
        MediaRef remote = MediaRef.remote(
                URI.create("https://example.test/a.pdf"), "application/pdf", "a.pdf");

        assertTrue(MediaLimits.of(1, 0).validate(List.of(remote)).isEmpty(),
                "a zero byte cap cannot bound bytes ARA was never told about");
        assertTrue(MediaLimits.of(0, 10_000).validate(List.of(remote)).isPresent(),
                "but the file still counts toward the file limit");
    }
}
