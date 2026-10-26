package dev.forgesworn.kithmoot.media.attachment

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class IsolatedAttachmentDecoderService : Service() {
    private var session: DecoderSession? = null

    override fun onBind(intent: Intent): IBinder = DecoderSession().also { session = it }

    override fun onUnbind(intent: Intent): Boolean {
        session?.close()
        stopSelf()
        Process.killProcess(Process.myPid())
        return false
    }

    override fun onDestroy() {
        session?.close()
        Process.killProcess(Process.myPid())
    }

    private class DecoderSession : IIsolatedAttachmentDecoder.Stub(), AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val started = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        @Volatile private var work: Future<*>? = null

        override fun decode(plaintext: SharedMemory, plaintextSize: Int, mime: String, callback: IIsolatedAttachmentCallback) {
            if (!started.compareAndSet(false, true) || cancelled.get()) {
                callback.onError(IIsolatedAttachmentDecoder.ERROR_CANCELLED)
                return
            }
            work = executor.submit {
                var output: SharedMemory? = null
                try {
                    val decoded = decodeOne(plaintext, plaintextSize, mime)
                    output = decoded.rgba
                    if (cancelled.get()) {
                        callback.onError(IIsolatedAttachmentDecoder.ERROR_CANCELLED)
                    } else {
                        callback.onDecoded(decoded.width, decoded.height, decoded.offsets, decoded.delays, decoded.rgba)
                    }
                } catch (failure: Throwable) {
                    val code = when (failure) {
                        is AttachmentLimitException -> IIsolatedAttachmentDecoder.ERROR_LIMIT
                        is InterruptedException -> IIsolatedAttachmentDecoder.ERROR_CANCELLED
                        is DecodeFailure -> failure.code
                        is OutOfMemoryError -> IIsolatedAttachmentDecoder.ERROR_LIMIT
                        else -> IIsolatedAttachmentDecoder.ERROR_CORRUPT
                    }
                    runCatching { callback.onError(code) }
                } finally {
                    plaintext.close()
                    output?.close()
                }
            }
        }

        override fun cancel() {
            cancelled.set(true)
            work?.cancel(true)
        }

        override fun close() {
            cancel()
            executor.shutdownNow()
        }
    }
}

private class DecodeFailure(val code: Int, message: String) : Exception(message)
private class DecodedPayload(val width: Int, val height: Int, val offsets: IntArray, val delays: IntArray, val rgba: SharedMemory)

private fun decodeOne(shared: SharedMemory, size: Int, mime: String): DecodedPayload {
    if (size !in 1..MAX_ATTACHMENT_PLAINTEXT_BYTES || size != shared.size) {
        throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_INVALID_INPUT, "Invalid plaintext input.")
    }
    if (mime !in supportedAttachmentMimes) throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_UNSUPPORTED_TYPE, "Unsupported image type.")
    val mapping = shared.mapReadOnly()
    val bytes = try {
        ByteArray(size).also { mapping.get(it) }
    } finally {
        SharedMemory.unmap(mapping)
    }
    try {
        val decoded = if (mime == "image/gif") decodeGif(bytes) else decodeStatic(bytes, mime)
        return writeFrames(decoded)
    } finally {
        bytes.fill(0)
    }
}

private class ServiceFrames(val width: Int, val height: Int, val delays: IntArray, val bitmaps: List<Bitmap>)

private fun decodeStatic(bytes: ByteArray, mime: String): ServiceFrames {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        if (info.mimeType != mime) throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_CORRUPT, "Decoded MIME type does not match the approved input.")
        checkedImageGeometry(info.size.width, info.size.height, 1)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener {
            throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_CORRUPT, "Partial images are rejected.")
        }
    }
    val safe = bitmap.asRgba()
    if (safe !== bitmap) bitmap.recycle()
    return ServiceFrames(safe.width, safe.height, intArrayOf(0), listOf(safe))
}

private fun decodeGif(bytes: ByteArray): ServiceFrames {
    val timing = GifTimingParser.parse(bytes)
    val geometry = checkedImageGeometry(timing.width, timing.height, timing.delaysMs.size)
    val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
        ?: throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_CORRUPT, "Invalid GIF.")
    if (movie.width() != timing.width || movie.height() != timing.height) {
        throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_CORRUPT, "GIF dimensions changed while decoding.")
    }
    val frames = ArrayList<Bitmap>(timing.delaysMs.size)
    try {
        var at = 0
        timing.seekDelaysMs.forEachIndexed { index, seekDelay ->
            // Movie treats an exact frame boundary as the preceding frame.
            movie.setTime(if (index == 0) at else Math.addExact(at, 1))
            val bitmap = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
            frames += bitmap
            val canvas = Canvas(bitmap)
            movie.draw(canvas, 0f, 0f)
            at = Math.addExact(at, seekDelay)
        }
        return ServiceFrames(geometry.width, geometry.height, timing.delaysMs.toIntArray(), frames)
    } catch (failure: Throwable) {
        frames.forEach { it.recycle() }
        throw failure
    }
}

private fun Bitmap.asRgba(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    return copy(Bitmap.Config.ARGB_8888, false)
        ?: throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_CORRUPT, "Image pixels could not be read.")
}

private fun writeFrames(decoded: ServiceFrames): DecodedPayload {
    val geometry = checkedImageGeometry(decoded.width, decoded.height, decoded.bitmaps.size)
    require(decoded.delays.size == decoded.bitmaps.size)
    val output = SharedMemory.create("kithmoot-decoded-rgba", geometry.rgbaBytes)
    var mapping: ByteBuffer? = null
    try {
        val buffer = output.mapReadWrite()
        mapping = buffer
        decoded.bitmaps.forEachIndexed { index, bitmap ->
            if (bitmap.width != geometry.width || bitmap.height != geometry.height || bitmap.config != Bitmap.Config.ARGB_8888) {
                throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_CORRUPT, "Decoder returned inconsistent frames.")
            }
            buffer.position(index * (geometry.rgbaBytes / decoded.bitmaps.size))
            bitmap.copyPixelsToBuffer(buffer)
        }
        SharedMemory.unmap(buffer)
        mapping = null
        if (!output.setProtect(OsConstants.PROT_READ)) throw DecodeFailure(IIsolatedAttachmentDecoder.ERROR_SERVICE, "Could not seal decoded pixels.")
        val frameBytes = geometry.rgbaBytes / decoded.bitmaps.size
        return DecodedPayload(geometry.width, geometry.height,
            IntArray(decoded.bitmaps.size) { it * frameBytes }, decoded.delays, output)
    } catch (failure: Throwable) {
        output.close()
        throw failure
    } finally {
        mapping?.let(SharedMemory::unmap)
        decoded.bitmaps.forEach { it.recycle() }
    }
}
