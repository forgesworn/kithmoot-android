package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Stable routing labels for an already admitted room. Neither is a capability.
 * Discovery persists through rekeys, so it can reveal repeated room presence. */
object RoomNearbyDiscovery {
    fun scope(rootRoomId: String): String {
        require(Regex("[0-9a-f]{64}").matches(rootRoomId))
        return digest("kithmoot/nearby/scope/v1", rootRoomId.hexToBytes()).toHex()
    }
    fun serviceUuid(scope: String): UUID {
        require(Regex("[0-9a-f]{64}").matches(scope))
        val bytes = digest("kithmoot/nearby/service/v1", scope.hexToBytes()).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 15) or 0x80).toByte() // custom UUID v8
        bytes[8] = ((bytes[8].toInt() and 63) or 0x80).toByte()
        return ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
    }
    private fun digest(domain: String, value: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(domain.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + value)
}
