package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.RoomStorageRecord
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The group storage list a saved room keeps across visits (S5b). */
class SavedRoomStorageTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val secret = Entropy.bytes(32)
    private val who = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)
    private val host = createRoomInvitation()
    private val url = encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays)
    private fun room() = SavedRoom.create(secret, who, url, relays, "The moot", now, null, host.invitation.canonicalInviter)
    private val sig = "ab".repeat(64)

    @Test fun `the newest list stands, a tie keeps what is held, and the same list from an op adds its signature`() {
        val fresh = room()
        assertNull(fresh.roomStorageRecord)
        val invited = fresh.withRoomStorage(RoomStorageRecord(listOf("https://grove.example.net"), 10))
        assertEquals(RoomStorageRecord(listOf("https://grove.example.net"), 10), invited.roomStorageRecord)
        assertSame(invited, invited.withRoomStorage(RoomStorageRecord(listOf("https://heath.example.net"), 9)), "older")
        assertSame(invited, invited.withRoomStorage(RoomStorageRecord(listOf("https://heath.example.net"), 10, sig)), "a tie with another list")
        val signed = invited.withRoomStorage(RoomStorageRecord(listOf("https://grove.example.net"), 10, sig))
        assertEquals(sig, signed.roomStorageRecord?.sig)
        val withdrawn = signed.withRoomStorage(RoomStorageRecord(emptyList(), 11, sig))
        assertEquals(RoomStorageRecord(emptyList(), 11, sig), withdrawn.roomStorageRecord)
        assertEquals(withdrawn.roomStorageRecord, SavedRoom.decode(withdrawn.json).roomStorageRecord)
    }

    @Test fun `a rejoin keeps the newer of the two lists`() {
        val held = room().withRoomStorage(RoomStorageRecord(listOf("https://grove.example.net"), 20, sig))
        assertEquals(20L, room().retainingHistory(held).roomStorageRecord?.version)
        val newer = room().withRoomStorage(RoomStorageRecord(emptyList(), 30))
        assertEquals(30L, newer.retainingHistory(held).roomStorageRecord?.version)
    }

    @Test fun `a list that is not canonical is never kept`() {
        val bad = JsonObject(room().json + ("roomStorage" to buildJsonObject {
            put("nodes", buildJsonArray { add(JsonPrimitive("https://grove.example.net/")) }); put("version", 1)
        }))
        assertFails { SavedRoom.decode(bad) }
        assertFails { room().withRoomStorage(RoomStorageRecord(listOf("http://grove.example.net"), 1)) }
    }
}
