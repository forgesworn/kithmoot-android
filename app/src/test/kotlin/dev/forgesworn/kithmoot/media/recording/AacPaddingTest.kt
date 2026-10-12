package dev.forgesworn.kithmoot.media.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AacPaddingTest {
    @Test fun physical_pixel_flush_packet_is_outside_the_original_source_interval() {
        assertTrue(isFinalAacPadding(35_349_333, 35_328_000, 35_345_687))
    }

    @Test fun both_microsecond_roundings_of_a_48khz_access_unit_are_supported() {
        assertTrue(isFinalAacPadding(42_666, 21_333, 40_000))
        assertTrue(isFinalAacPadding(42_667, 21_333, 40_000))
    }

    @Test fun source_samples_and_missing_or_reordered_intervals_cannot_be_trimmed() {
        assertFalse(isFinalAacPadding(42_666, 21_333, 42_666))
        assertFalse(isFinalAacPadding(42_666, 21_333, 50_000))
        assertFalse(isFinalAacPadding(42_666, -1, 40_000))
        assertFalse(isFinalAacPadding(42_666, 21_333, 21_333))
        assertFalse(isFinalAacPadding(21_000, 21_333, 20_000))
        assertFalse(isFinalAacPadding(64_000, 21_333, 40_000))
        assertFalse(isFinalAacPadding(Long.MAX_VALUE, 21_333, 40_000))
    }
}
