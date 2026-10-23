package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomAdmission
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import dev.forgesworn.kithmoot.protocol.RoomInvitationHost
import dev.forgesworn.kithmoot.protocol.decodePersistentInvitation
import dev.forgesworn.kithmoot.protocol.encodePersistentInvitation
import dev.forgesworn.kithmoot.protocol.invitationStorageOf
import dev.forgesworn.kithmoot.protocol.isInvitationStorage
import dev.forgesworn.kithmoot.protocol.isStorageNodeBase
import dev.forgesworn.kithmoot.protocol.storageNodeServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fold-kit 0.14.0's `vectors/storage-vectors.json`, copied byte for byte (never
 * edited): a room's own storage nodes in its group invitation (S5). Every
 * invitation this client can write is rebuilt from its recorded draws and must
 * come out identical; every one is read by the real decoder and must give the
 * recorded admission; every refused entry and list refuses the whole envelope.
 * Merging copies is the app's (`requestPersistentAdmission`), and is checked
 * against the same file in the app's tests.
 */
class StorageVectorsTest {
    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/storage-vectors.json")) { "storage-vectors.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val vectors: List<JsonObject> get() = root.child("groups").list("storage").map { it.jsonObject }
    private fun vec(name: String): JsonObject = vectors.single { it.text("name") == name }
    private fun draws(value: JsonObject): List<ByteArray> = value.strings("randomHex").map { it.hexToBytes() }
    private fun event(value: JsonObject): NostrEvent = NostrEvent.fromJson(value)
    private fun invitation(input: JsonObject): RoomInvitation = input.child("invitation").let {
        RoomInvitation(it.bytes("bearerHex"), it.text("inviter"), it.flag("persistent"))
    }
    private fun welcomeKey(invitation: RoomInvitation): ByteArray =
        Digests.hkdfSha256(invitation.bearer, null, "kithmoot/v3/group-invitation-key".toByteArray(), 32)

    private fun assertAdmission(label: String, expected: JsonObject, actual: RoomAdmission?) {
        assertNotNull(label, actual)
        assertEquals("$label secret", expected.text("secretHex"), actual!!.secret.toHex())
        assertEquals("$label epoch", expected.number("epoch").toInt(), actual.epoch)
        assertEquals("$label ends", if ("endsAt" in expected) expected.number("endsAt") else null, actual.endsAt)
        assertEquals("$label relays", if ("relays" in expected) expected.strings("relays") else null, actual.relays)
        assertEquals("$label destruct", "destruct" in expected && expected.flag("destruct"), actual.destruct)
        assertEquals("$label storage", if ("storage" in expected) expected.strings("storage") else null, actual.storage)
        assertEquals("$label storageAt", if ("storageAt" in expected) expected.number("storageAt") else null, actual.storageAt)
    }

    @Test fun `the file is in the KithMoot vector format and every vector is counted`() {
        assertEquals("kithmoot/v1", root.text("protocolVersion"))
        assertEquals(setOf("storage"), root.child("groups").keys)
        assertEquals(
            listOf("storage-invitation", "storage-full-order", "storage-withdrawn", "storage-absent", "storage-accepts",
                "storage-entry-refusals", "storage-list-refusals", "storage-merge", "storage-relays-independent", "storage-strict-old-reader"),
            vectors.map { it.text("name") },
        )
        assertEquals(2, vectors.count { it.text("kind") == "negative" })
    }

    @Test fun `an invitation listing storage rebuilds byte for byte and reads back as recorded`() {
        for (name in listOf("storage-invitation", "storage-full-order", "storage-withdrawn")) {
            val v = vec(name)
            val i = v.child("input")
            val expected = v.child("expected")
            val invitation = invitation(i)
            val random = draws(i)
            val rebuilt = encodePersistentInvitation(RoomInvitationHost(invitation, i.bytes("inviterSkHex")), i.bytes("roomSecretHex"),
                i.number("createdAt"), random[0], random[1],
                ends = if ("endsAt" in i) i.number("endsAt") else null,
                relays = if ("relays" in i) i.strings("relays") else null,
                destruct = "destruct" in i && i.flag("destruct"),
                storage = i.strings("storage"))
            assertEquals(name, i.child("event"), rebuilt.toJson())
            assertEquals("$name body", expected.text("bodyJson"), Nip44.decrypt(rebuilt.content, welcomeKey(invitation)))
            assertAdmission(name, expected.child("admission"), decodePersistentInvitation(event(i.child("event")), invitation))
        }
    }

    @Test fun `copies written before storage existed say nothing about it`() {
        val v = vec("storage-absent")
        val i = v.child("input")
        val invitation = invitation(i)
        val recorded = i.child("recorded0130")
        assertAdmission("plain", v.child("expected").child("plainAdmission"), decodePersistentInvitation(event(recorded.child("plain").child("event")), invitation))
        assertAdmission("full", v.child("expected").child("fullAdmission"), decodePersistentInvitation(event(recorded.child("full").child("event")), invitation))
    }

    @Test fun `every accepted entry is canonical, names its server, and reads back alone`() {
        val v = vec("storage-accepts")
        val i = v.child("input")
        val invitation = invitation(i)
        for (raw in i.list("cases")) {
            val case = raw.jsonObject
            val entry = case.text("entry")
            assertTrue(entry, isStorageNodeBase(entry))
            assertEquals(entry, case.text("server"), storageNodeServer(entry))
            assertEquals("$entry body", case.text("bodyJson"), Nip44.decrypt(case.child("event").text("content"), welcomeKey(invitation)))
            val admission = decodePersistentInvitation(event(case.child("event")), invitation)
            assertEquals(entry, listOf(entry), admission?.storage)
            assertEquals(entry, case.child("event").number("created_at"), admission?.storageAt)
        }
    }

    @Test fun `every refused entry is not canonical, and refuses the whole envelope`() {
        val v = vec("storage-entry-refusals")
        val i = v.child("input")
        val invitation = invitation(i)
        val cases = i.list("cases").map { it.jsonObject }
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val label = case.text("label")
            val entry = case.text("entry")
            assertFalse(label, isStorageNodeBase(entry))
            assertNull(label, storageNodeServer(entry))
            assertFalse(label, isInvitationStorage(listOf(entry)))
            assertEquals("$label body", case.text("bodyJson"), Nip44.decrypt(case.child("event").text("content"), welcomeKey(invitation)))
            assertNull(label, decodePersistentInvitation(event(case.child("event")), invitation))
        }
    }

    @Test fun `every refused list refuses the whole envelope`() {
        val v = vec("storage-list-refusals")
        val i = v.child("input")
        val invitation = invitation(i)
        for (raw in i.list("cases")) {
            val case = raw.jsonObject
            val label = case.text("label")
            val body = Nip44.decrypt(case.child("event").text("content"), welcomeKey(invitation))
            assertEquals("$label body", case.text("bodyJson"), body)
            assertNull("$label list", invitationStorageOf(Json.parseToJsonElement(body).jsonObject["storage"]))
            assertNull(label, decodePersistentInvitation(event(case.child("event")), invitation))
        }
    }

    @Test fun `each copy of the merge vectors reads alone with its own list`() {
        for (name in listOf("storage-merge", "storage-relays-independent", "storage-strict-old-reader")) {
            val i = vec(name).child("input")
            val invitation = invitation(i)
            for (raw in i.list("copies")) {
                val copy = raw.jsonObject
                val admission = decodePersistentInvitation(event(copy.child("event")), invitation)
                assertNotNull("$name ${copy.text("name")}", admission)
                assertEquals("$name ${copy.text("name")}", if ("storage" in copy) copy.strings("storage") else null, admission!!.storage)
                assertEquals("$name ${copy.text("name")} at", if ("storage" in copy) copy.number("createdAt") else null, admission.storageAt)
            }
        }
    }
}
