package dev.forgesworn.kithmoot.media.effects

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

/** One frame's worth of "how sure are we that this pixel is the person":
 *  row-major, `width * height` entries, each 0 to 1. */
class SegmentationMask(val data: FloatArray, val width: Int, val height: Int)

interface PersonSegmenter {
    /** The mask for this frame, or null if the segmenter had nothing to say
     *  about it. Null is an ordinary answer, not a failure. */
    fun segment(bitmap: Bitmap, timestampMs: Long): SegmentationMask?
    fun close()
}

/**
 * MediaPipe's Selfie Segmenter, wrapped as the one thing the compositor asks
 * for.
 *
 * ## Why the model is in the APK
 *
 * Every MediaPipe example downloads the `.tflite` from Google's CDN the first
 * time it is needed. Doing that would mean that switching a background on - in
 * an application whose whole claim is that no operator can see you - tells a
 * third party your IP address and that you are about to join a call. The model
 * is 244 KiB in `assets/models`; see the README beside it.
 *
 * ## Why it runs on the CPU
 *
 * The model is 256x256 and the delegate choice is not free either way: the GPU
 * delegate has to hand the confidence mask back across the bus every frame, and
 * on the handsets this has to work on that readback is the expensive half. CPU
 * is also the one that behaves the same on every device, which matters more
 * than a few milliseconds for a feature nobody has yet run on a phone.
 * [DELEGATE] is one line to change when there is a measurement to change it
 * with.
 */
class MediaPipeSegmenter private constructor(private var inner: ImageSegmenter?) : PersonSegmenter {

    private var lastTimestamp = -1L

    override fun segment(bitmap: Bitmap, timestampMs: Long): SegmentationMask? {
        val segmenter = inner ?: return null
        // VIDEO mode wants strictly increasing timestamps and throws on a
        // repeat, which two frames landing in the same millisecond will hand it.
        val stamp = if (timestampMs <= lastTimestamp) lastTimestamp + 1 else timestampMs
        lastTimestamp = stamp

        val image: MPImage = BitmapImageBuilder(bitmap).build()
        val result = segmenter.segmentForVideo(image, stamp)
        val masks: List<MPImage> = result.confidenceMasks().orElse(null) ?: emptyList()
        try {
            val mask = personMask(masks) ?: return null
            // Copied out before the masks are closed: the buffer underneath is
            // owned by the task and is invalid the moment it is released.
            val floats = ByteBufferExtractor.extract(mask)
                .duplicate()
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            val data = FloatArray(mask.width * mask.height)
            floats.get(data, 0, minOf(data.size, floats.remaining()))
            return SegmentationMask(data, mask.width, mask.height)
        } finally {
            for (one in masks) runCatching { one.close() }
            runCatching { image.close() }
        }
    }

    override fun close() {
        runCatching { inner?.close() }
        inner = null
    }

    companion object {
        /** Where the bundled model sits under `app/src/main/assets`. */
        const val MODEL_ASSET = "models/selfie_segmenter.tflite"

        private val DELEGATE = Delegate.CPU

        /**
         * Which confidence mask is the person.
         *
         * The two-class selfie segmenter emits masks in category order,
         * background first and person second, so with two masks the person is
         * the last one. Some builds emit a single foreground mask instead, in
         * which case there is no choice to make. Getting this wrong is not
         * subtle - the person goes in the sea and the room stays - which is why
         * it is a named function rather than a subscript.
         */
        fun personMask(masks: List<MPImage>): MPImage? = masks.lastOrNull()

        /** Loads the model. Throws if it cannot, which the compositor turns
         *  into a dropped frame rather than a published room. */
        fun open(context: Context): MediaPipeSegmenter {
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .setDelegate(DELEGATE)
                        .build(),
                )
                .setRunningMode(RunningMode.VIDEO)
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build()
            return MediaPipeSegmenter(ImageSegmenter.createFromOptions(context, options))
        }
    }
}
