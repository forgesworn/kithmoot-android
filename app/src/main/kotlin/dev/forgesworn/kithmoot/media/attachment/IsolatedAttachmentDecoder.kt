package dev.forgesworn.kithmoot.media.attachment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.SharedMemory
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AttachmentDecodeException(message: String) : Exception(message)

class DecodedAttachment internal constructor(
    val width: Int,
    val height: Int,
    val frames: List<Bitmap>,
    val delaysMs: IntArray,
) {
    fun recycle() = frames.forEach { it.recycle() }
}

private class SharedDecoded(val width: Int, val height: Int, val offsets: IntArray, val delays: IntArray, val rgba: SharedMemory)

suspend fun decodeAttachmentIsolated(context: Context, plaintext: ByteArray, mime: String): DecodedAttachment {
    if (plaintext.size !in 1..MAX_ATTACHMENT_PLAINTEXT_BYTES || mime !in supportedAttachmentMimes) {
        throw AttachmentDecodeException("This image cannot be displayed in the Android viewer.")
    }
    val input = readOnlyInput(plaintext)
    try {
        val decoded = try {
            withTimeout(30_000) { bindAndDecode(context.applicationContext, input, plaintext.size, mime) }
        } finally {
            input.close()
        }
        try {
            return copyValidatedFrames(decoded, mime)
        } finally {
            decoded.rgba.close()
        }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        throw AttachmentDecodeException("The isolated image decoder timed out.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AttachmentDecodeException) {
        throw failure
    } catch (failure: Exception) {
        throw AttachmentDecodeException(failure.message ?: "The isolated image decoder failed.")
    }
}

private fun readOnlyInput(plaintext: ByteArray): SharedMemory {
    val shared = SharedMemory.create("kithmoot-attachment-plaintext", plaintext.size)
    var mapping: java.nio.ByteBuffer? = null
    try {
        val buffer = shared.mapReadWrite()
        mapping = buffer
        buffer.put(plaintext)
        SharedMemory.unmap(buffer)
        mapping = null
        if (!shared.setProtect(OsConstants.PROT_READ)) throw AttachmentDecodeException("Could not protect image plaintext.")
        return shared
    } catch (failure: Throwable) {
        shared.close()
        throw failure
    } finally {
        mapping?.let(SharedMemory::unmap)
    }
}

private suspend fun bindAndDecode(context: Context, input: SharedMemory, size: Int, mime: String): SharedDecoded =
    suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean()
        var bound = false
        var remote: IIsolatedAttachmentDecoder? = null
        lateinit var connection: ServiceConnection

        fun unbind() {
            if (!bound) return
            bound = false
            runCatching { remote?.cancel() }
            runCatching { context.unbindService(connection) }
        }
        fun fail(message: String) {
            if (finished.compareAndSet(false, true)) {
                unbind()
                continuation.resumeWithException(AttachmentDecodeException(message))
            }
        }
        val callback = object : IIsolatedAttachmentCallback.Stub() {
            override fun onDecoded(width: Int, height: Int, frameOffsets: IntArray, frameDelaysMs: IntArray, rgba: SharedMemory) {
                if (!finished.compareAndSet(false, true)) {
                    rgba.close()
                    return
                }
                unbind()
                continuation.resume(SharedDecoded(width, height, frameOffsets, frameDelaysMs, rgba)) { _, discarded, _ ->
                    discarded.rgba.close()
                }
            }

            override fun onError(errorCode: Int) {
                fail(when (errorCode) {
                    IIsolatedAttachmentDecoder.ERROR_LIMIT -> "This image exceeds the Android viewer limits."
                    IIsolatedAttachmentDecoder.ERROR_UNSUPPORTED_TYPE -> "This file type cannot be displayed in the Android image viewer."
                    IIsolatedAttachmentDecoder.ERROR_CORRUPT -> "The image is damaged or incomplete."
                    IIsolatedAttachmentDecoder.ERROR_CANCELLED -> "The isolated image decoding was cancelled."
                    else -> "The isolated image decoder could not open this image."
                })
            }
        }
        val death = IBinder.DeathRecipient { fail("The isolated image decoder stopped unexpectedly.") }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished.get()) {
                    unbind()
                    return
                }
                try {
                    remote = IIsolatedAttachmentDecoder.Stub.asInterface(binder)
                    binder.linkToDeath(death, 0)
                    remote!!.decode(input, size, mime, callback)
                } catch (failure: Exception) {
                    fail("The isolated image decoder could not be started.")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = fail("The isolated image decoder stopped unexpectedly.")
            override fun onBindingDied(name: ComponentName) = fail("The isolated image decoder binding died.")
            override fun onNullBinding(name: ComponentName) = fail("The isolated image decoder returned no binding.")
        }
        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) unbind()
        }
        try {
            val intent = Intent(context, IsolatedAttachmentDecoderService::class.java)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) fail("The isolated image decoder is unavailable.")
        } catch (failure: Exception) {
            fail("The isolated image decoder is unavailable.")
        }
    }

private fun copyValidatedFrames(decoded: SharedDecoded, mime: String): DecodedAttachment {
    try {
        validateDecodedLayout(decoded.width, decoded.height, decoded.offsets, decoded.delays, decoded.rgba.size, mime)
    } catch (failure: Exception) {
        throw AttachmentDecodeException("The isolated image decoder returned an invalid result.")
    }
    val mapping = decoded.rgba.mapReadOnly()
    val frames = mutableListOf<Bitmap>()
    try {
        decoded.offsets.forEach { offset ->
            val bitmap = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            frames += bitmap
            mapping.position(offset)
            bitmap.copyPixelsFromBuffer(mapping)
        }
        return DecodedAttachment(decoded.width, decoded.height, frames, decoded.delays.copyOf())
    } catch (failure: Throwable) {
        frames.forEach { it.recycle() }
        throw AttachmentDecodeException("The isolated image decoder returned unreadable pixels.")
    } finally {
        SharedMemory.unmap(mapping)
    }
}
