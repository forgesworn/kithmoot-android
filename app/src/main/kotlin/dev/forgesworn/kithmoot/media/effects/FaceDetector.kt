package dev.forgesworn.kithmoot.media.effects

import android.graphics.Bitmap

interface FaceLocator {
    fun detect(bitmap: Bitmap, timestampMs: Long): List<FaceBox>
    fun close()
}
