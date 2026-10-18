package dev.forgesworn.kithmoot.media.effects

import android.graphics.Bitmap

data class SegmentationMask(val data: FloatArray, val width: Int, val height: Int)

interface PersonSegmenter : AutoCloseable {
    fun segment(bitmap: Bitmap, timestampMs: Long): SegmentationMask?
    override fun close()
}

/** No network or vendor-model fallback. Our trained model must first pass
 * the qualification gates documented in kithmoot/vision. */
class UnavailableSegmenter : PersonSegmenter {
    override fun segment(bitmap: Bitmap, timestampMs: Long): SegmentationMask? = null
    override fun close() = Unit
}
