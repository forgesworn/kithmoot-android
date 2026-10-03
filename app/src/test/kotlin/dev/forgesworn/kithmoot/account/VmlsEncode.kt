package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.io.ByteArrayOutputStream

/**
 * Test support: a canonical encoder for the seven-key unsigned
 * `leaf-binding/1` body, the inverse of `LeafBinding.readUnsigned`. It builds
 * the requests the MLS device vault tests sign. The engine makes these in
 * production; nothing outside tests encodes them.
 */
internal object VmlsEncode {
    fun unsignedBinding(
        leafId: ByteArray,
        signatureKey: ByteArray,
        credential: NostrEvent,
        device: String,
        expiresAt: Long,
        homeBox: ByteArray,
        version: Long = 1,
    ): ByteArray = ByteArrayOutputStream().apply {
        head(5, 7)
        uint(1); uint(version)
        uint(2); bytes("vmls1".toByteArray(Charsets.US_ASCII) + leafId)
        uint(3); bytes(signatureKey)
        uint(4)
        head(5, 5)
        uint(1); bytes(credential.pubkey.hexToBytes())
        uint(2); uint(credential.createdAt)
        uint(3); head(4, credential.tags.size.toLong())
        for (tag in credential.tags) { head(4, tag.size.toLong()); tag.forEach { text(it) } }
        uint(4); text(credential.content)
        uint(5); bytes(credential.sig.hexToBytes())
        uint(5); bytes(device.hexToBytes())
        uint(6); uint(expiresAt)
        uint(7); bytes(homeBox)
    }.toByteArray()

    private fun ByteArrayOutputStream.head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> write(m or value.toInt())
            value < 0x100 -> { write(m or 24); write(value.toInt()) }
            value < 0x10000 -> { write(m or 25); be(value, 2) }
            value < 0x100000000 -> { write(m or 26); be(value, 4) }
            else -> { write(m or 27); be(value, 8) }
        }
    }

    private fun ByteArrayOutputStream.be(value: Long, width: Int) {
        for (i in width - 1 downTo 0) write(((value ushr (8 * i)) and 0xff).toInt())
    }

    private fun ByteArrayOutputStream.uint(value: Long) = head(0, value)
    private fun ByteArrayOutputStream.uint(value: Int) = head(0, value.toLong())
    private fun ByteArrayOutputStream.bytes(b: ByteArray) { head(2, b.size.toLong()); write(b) }
    private fun ByteArrayOutputStream.text(s: String) { val b = s.toByteArray(Charsets.UTF_8); head(3, b.size.toLong()); write(b) }
}
