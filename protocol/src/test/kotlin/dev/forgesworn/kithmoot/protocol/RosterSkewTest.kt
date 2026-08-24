package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A roster timestamp is chosen by the device that publishes it, so it has to be
 * bounded. Mirrors the M4 cases in `src/roster.test.ts`.
 */
class RosterSkewTest {

    private val now = 1_800_000_000L
    private val room = deriveRoom(ByteArray(32) { 9 })
    private val participantSecretKey = ByteArray(32) { (it + 3).toByte() }
    private val deviceSecretKey = ByteArray(32) { (it + 60).toByte() }
    private val device = Schnorr.publicKeyHex(deviceSecretKey)

    private fun entry(updatedAt: Long = now, claims: Map<String, Long> = mapOf("mic" to now)) = RosterEntry(
        participant = Schnorr.publicKeyHex(participantSecretKey),
        device = device,
        credential = createDeviceCredential(
            participantSecretKey = participantSecretKey,
            devicePubkey = device,
            roomId = room.roomId,
            expiresAt = now + 3600,
            createdAt = now - 10,
        ),
        tracks = listOf(TrackRef("t1", "screen")),
        claims = claims,
        updatedAt = updatedAt,
    )

    private fun decode(entry: RosterEntry): RosterEntry? = decodeRosterEvent(
        event = encodeRosterEvent(entry, room.roomId, room.roomKey, deviceSecretKey),
        roomId = room.roomId,
        roomKey = room.roomKey,
        now = now,
    )

    @Test
    fun `BUG (M4)- an entry stamped further into the future than clock skew allows is refused`() {
        assertNull(decode(entry(updatedAt = 9_000_000_000_000_000L)))
    }

    @Test
    fun `an entry from a device whose clock is a little fast is accepted`() {
        val ahead = now + MAX_FUTURE_SKEW_SECONDS - 1
        assertEquals(ahead, decode(entry(updatedAt = ahead))?.updatedAt)
    }

    @Test
    fun `BUG (M4)- a role claim stamped further into the future than clock skew allows is dropped`() {
        val decoded = decode(entry(claims = mapOf("mic" to 9_000_000_000_000_000L, "monitor" to now)))
        // The device stays in the room - a bad claim costs the claim, not the
        // device's presence - but the claim itself is gone.
        assertNotNull(decoded)
        assertNull(decoded?.claims?.get("mic"))
        assertEquals(now, decoded?.claims?.get("monitor"))
    }
}
