package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A track advert may say the device silenced it at the source.
 *
 * A device with its microphone off looks exactly like a device with no
 * microphone at all if the only evidence is an absent track, and only one of
 * those people is still in the conversation. `muted` is the difference, and it
 * is a claim about this device's own track - never about a listener's volume,
 * which is nobody else's business and never on the wire.
 *
 * Mirrors the `muted` cases in the web client's `src/roster.test.ts` and the
 * `muted-mic-track` and `muted-false-drops-to-absent` shared vectors.
 */
class RosterMutedTrackTest {

    private val now = 1_800_000_000L
    private val room = deriveRoom(ByteArray(32) { 11 })
    private val participantSecretKey = ByteArray(32) { (it + 5).toByte() }
    private val deviceSecretKey = ByteArray(32) { (it + 70).toByte() }
    private val device = Schnorr.publicKeyHex(deviceSecretKey)

    private fun entry(tracks: List<TrackRef>) = RosterEntry(
        participant = Schnorr.publicKeyHex(participantSecretKey),
        device = device,
        credential = createDeviceCredential(
            participantSecretKey = participantSecretKey,
            devicePubkey = device,
            roomId = room.roomId,
            expiresAt = now + 3600,
            createdAt = now - 10,
        ),
        tracks = tracks,
        claims = emptyMap(),
        updatedAt = now,
    )

    private fun roundTrip(tracks: List<TrackRef>): RosterEntry? = decodeRosterEvent(
        event = encodeRosterEvent(
            entry = entry(tracks),
            roomId = room.roomId,
            roomKey = room.roomKey,
            deviceSecretKey = deviceSecretKey,
        ),
        roomId = room.roomId,
        roomKey = room.roomKey,
        now = now,
    )

    /** The advert objects as they are actually written, before encryption. */
    private fun advertsOf(tracks: List<TrackRef>): List<JsonObject> =
        (entry(tracks).toJson()["tracks"] as JsonArray).map { it.jsonObject }

    @Test
    fun `an unmuted advert writes no muted key at all`() {
        // The wire stays byte-identical for everybody who never mutes, and for
        // every client that has never heard of the field.
        val advert = advertsOf(listOf(TrackRef("m1", "mic"))).single()

        assertEquals(setOf("trackId", "role"), advert.keys)
    }

    @Test
    fun `the key order is trackId, role, muted`() {
        val advert = advertsOf(listOf(TrackRef("m1", "mic", muted = true))).single()

        assertEquals(listOf("trackId", "role", "muted"), advert.keys.toList())
    }

    @Test
    fun `a muted advert survives the round trip`() {
        val decoded = roundTrip(listOf(TrackRef("m1", "mic", muted = true)))
        assertNotNull(decoded)

        assertEquals(true, decoded!!.tracks.single().muted)
    }

    @Test
    fun `only the literal true is a mute claim`() {
        // The same rule as the farewell flag, and for the same reason: it
        // decides what the room shows about a person, so a looser client's
        // `false`, `1` or `"yes"` is not one.
        for (written in listOf("false", "1", "\"true\"", "null")) {
            val json = Json.parseToJsonElement(
                """{"trackId":"m1","role":"mic","muted":$written}""",
            ).jsonObject
            val advert = TrackRef(
                trackId = json.getValue("trackId").jsonPrimitive.content,
                role = json.getValue("role").jsonPrimitive.content,
                muted = (json["muted"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull(),
            )
            assertFalse("`$written` must not read as a mute claim", advert.muted == true)
        }
    }

    @Test
    fun `a decoded advert never carries a muted value that is not the claim`() {
        // Nothing downstream should have to ask whether `false` means the same
        // as absent, so the decoder makes sure it never sees one.
        val decoded = roundTrip(listOf(TrackRef("m1", "mic", muted = false)))
        assertNotNull(decoded)

        assertNull(decoded!!.tracks.single().muted)
        assertEquals(
            setOf("trackId", "role"),
            (decoded.toJson()["tracks"] as JsonArray).single().jsonObject.keys,
        )
    }

    @Test
    fun `a reader that has never heard of the field still decodes everything else`() {
        val decoded = roundTrip(listOf(TrackRef("m1", "mic", muted = true), TrackRef("c1", "camera")))
        assertNotNull(decoded)

        assertEquals(listOf("m1", "c1"), decoded!!.tracks.map { it.trackId })
        assertEquals(listOf("mic", "camera"), decoded.tracks.map { it.role })
        assertTrue(decoded.tracks[0].muted == true)
        assertNull(decoded.tracks[1].muted)
    }
}
