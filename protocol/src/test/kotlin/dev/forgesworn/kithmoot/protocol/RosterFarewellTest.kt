package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last entry a device publishes carries `left: true`, so everybody else
 * drops it at once rather than when its presence lapses. Departure is a stated
 * fact rather than a guess from an empty track list: a device with everything
 * switched off looks exactly like one on its way out, and only one of them
 * should vanish. Mirrors the farewell cases in `src/roster.test.ts`.
 */
class RosterFarewellTest {

    private val now = 1_800_000_000L
    private val room = deriveRoom(ByteArray(32) { 9 })
    private val participantSecretKey = ByteArray(32) { (it + 3).toByte() }
    private val deviceSecretKey = ByteArray(32) { (it + 60).toByte() }
    private val device = Schnorr.publicKeyHex(deviceSecretKey)

    private fun entry(reply: Boolean = false, left: Boolean = false) = RosterEntry(
        participant = Schnorr.publicKeyHex(participantSecretKey),
        device = device,
        credential = createDeviceCredential(
            participantSecretKey = participantSecretKey,
            devicePubkey = device,
            roomId = room.roomId,
            expiresAt = now + 3600,
            createdAt = now - 10,
        ),
        tracks = emptyList(),
        claims = emptyMap(),
        updatedAt = now,
        reply = reply,
        left = left,
    )

    private fun decode(event: NostrEvent): RosterEntry? =
        decodeRosterEvent(event, room.roomId, room.roomKey, now)

    private fun plaintext(event: NostrEvent): JsonObject =
        Json.parseToJsonElement(Nip44.decrypt(event.content, room.roomKey)).jsonObject

    @Test
    fun `a farewell round-trips as left and reply`() {
        val decoded = decode(encodeRosterEvent(entry(reply = true, left = true), room.roomId, room.roomKey, deviceSecretKey))
        assertNotNull(decoded)
        assertTrue(decoded!!.left)
        assertTrue(decoded.reply)
    }

    @Test
    fun `an entry that is not a farewell carries neither field on the wire`() {
        // Byte-identical to what every client published before the fields
        // existed, so a reader that never learned them sees nothing new.
        val json = plaintext(encodeRosterEvent(entry(), room.roomId, room.roomKey, deviceSecretKey))
        assertFalse(json.containsKey("left"))
        assertFalse(json.containsKey("reply"))
    }

    @Test
    fun `only an honest JSON true is a departure`() {
        // A farewell removes somebody from the room, so a looser
        // implementation's "yes" or 1 must not read as one.
        for (hostile in listOf(JsonPrimitive("yes"), JsonPrimitive(1), JsonPrimitive("true"))) {
            val tampered = JsonObject(entry().toJson() + ("left" to hostile))
            val event = Events.sign(
                secretKey = deviceSecretKey,
                kind = KIND_ROSTER,
                createdAt = now,
                tags = listOf(listOf("d", room.roomId)),
                content = Nip44.encrypt(tampered.toString(), room.roomKey, ByteArray(32) { 5 }),
                auxRand = ByteArray(32) { 6 },
            )
            val decoded = decode(event)
            assertNotNull("left=$hostile", decoded)
            assertEquals("left=$hostile", false, decoded!!.left)
        }
    }
}
