package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.GROUP_GRANT_KIND
import dev.forgesworn.kithmoot.protocol.GrantReceiver
import dev.forgesworn.kithmoot.protocol.GroupEventVerdict
import dev.forgesworn.kithmoot.protocol.ParsedGrant
import dev.forgesworn.kithmoot.protocol.ParsedReset
import dev.forgesworn.kithmoot.protocol.buildGrant
import dev.forgesworn.kithmoot.protocol.buildReset
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.parseGroupEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wildbloom-node's `vectors/group-grants-v1.json` (at 1232de0, the copy the web
 * client's tests also read, byte for byte): every grant event gets the verdict
 * a node gives it on rules 1 to 7, every well-formed event this client could
 * build is rebuilt byte for byte, and the receiver keeps what a device should.
 */
class GroupGrantVectorsTest {
    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/group-grants-v1.json")) { "group-grants-v1.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val keepers get() = root.child("node").list("enrolments").map { it.jsonObject.text("keeper") }
    private val servers get() = root.child("node").strings("servers")
    private val cases get() = root.list("grantEvents").map { it.jsonObject }
    private fun case(name: String) = cases.single { it.text("name") == name }
    private fun secretFor(pubkey: String): ByteArray? =
        root.child("keys").values.map { it.jsonObject }.firstOrNull { it.text("pubkey") == pubkey }?.bytes("secret")
    private val structural = setOf("malformed", "lifetime", "signature", "keeper", "server", "future", "expired")
    private val zeroAux = ByteArray(32)

    @Test fun `the profile is group-grants 1 and the rooms derive from their secrets`() {
        assertEquals("group-grants/1", root.text("profile"))
        assertEquals(GROUP_GRANT_KIND.toLong(), root.number("kind"))
        for (room in root.child("rooms").values.map { it.jsonObject }) assertEquals(room.text("roomId"), deriveRoom(room.bytes("secretHex")).roomId)
    }

    @Test fun `every grant event gets the structural verdict the vector states`() {
        assertEquals(37, cases.size)
        for (c in cases) {
            val verdict = parseGroupEvent(c["event"], now = c.number("now"), keepers = keepers, servers = servers)
            val expect = c.child("expect")
            if (expect.text("reason") in structural) {
                assertTrue(c.text("name"), verdict is GroupEventVerdict.Refused)
                verdict as GroupEventVerdict.Refused
                assertEquals(c.text("name"), expect.text("reason"), verdict.reason)
                assertEquals(c.text("name"), expect.number("status").toInt(), verdict.status)
            } else {
                // Accepted, or refused on node state (rule 8): structurally fine.
                assertTrue(c.text("name"), verdict is GroupEventVerdict.Ok)
            }
        }
    }

    @Test fun `every well-formed event in keeper tag order rebuilds byte for byte`() {
        val events: List<JsonElement> = cases.map { it.getValue("event") } +
            root.list("sequences").flatMap { s -> s.jsonObject.list("steps").mapNotNull { it.jsonObject["post"] } }
        var rebuilt = 0
        for (raw in events) {
            val p = (parseGroupEvent(raw) as? GroupEventVerdict.Ok)?.parsed ?: continue
            val secret = secretFor(p.signer) ?: continue
            val event = when (p) {
                is ParsedReset -> buildReset(secret, p.roomId, p.server, p.createdAt, p.expiration, zeroAux)
                is ParsedGrant -> runCatching {
                    buildGrant(secret, p.roomId, p.device, p.server, p.scopes, p.bytes?.toLong(), p.grantId, p.createdAt, p.expiration, p.status, zeroAux)
                }.getOrNull() ?: continue
            }
            // Only events whose tags are in the order a keeper writes them rebuild; the rest are out of order on purpose.
            if (raw.jsonObject.getValue("tags") != event.toJson().getValue("tags")) continue
            assertEquals(raw.jsonObject, event.toJson())
            rebuilt++
        }
        assertTrue("rebuilt $rebuilt", rebuilt > 30)
    }

    @Test fun `the receiver keeps its own grant, every revocation and reset, and nothing for another room`() {
        val good = case("good-grant").getValue("event")
        val grant = (parseGroupEvent(good) as GroupEventVerdict.Ok).parsed as ParsedGrant
        val roomA = root.child("rooms").child("a").text("roomId")
        val receiver = GrantReceiver(grant.signer, roomA, grant.device)
        assertTrue(receiver.offer(good))
        assertFalse("the same event again", receiver.offer(good))
        assertEquals(listOf(grant.grantId), receiver.grantsFor("grove.example.net", 1_800_000_000).map { it.grantId })
        assertEquals(good.jsonObject["id"]!!.jsonPrimitive.content, receiver.ownToPost("grove.example.net", 1_800_000_000)?.id)
        receiver.acknowledge("grove.example.net", grant.event.id)
        assertNull("acknowledged", receiver.ownToPost("grove.example.net", 1_800_000_000))
        assertTrue("expired at its end", receiver.grantsFor("grove.example.net", grant.expiration).isEmpty())

        // Another device's grant is not kept; another room's is not either.
        val stranger = GrantReceiver(grant.signer, roomA, "11".repeat(32))
        assertFalse(stranger.offer(good))
        assertFalse(GrantReceiver(grant.signer, root.child("rooms").child("b").text("roomId"), grant.device).offer(good))
        // Signed by a key that is not the room's authority.
        assertFalse(GrantReceiver("22".repeat(32), roomA, grant.device).offer(good))

        // A reset at the node ends it; a revocation carried to the node.
        val reset = case("good-reset").getValue("event")
        assertTrue(receiver.offer(reset))
        assertTrue(receiver.grantsFor("grove.example.net", 1_800_000_000).isEmpty())
        assertTrue(receiver.lockedOut("grove.example.net", 1_800_000_000))
        assertEquals(1, receiver.carry("grove.example.net", 1_800_000_000).size)

        // The snapshot restores to the same receiver, and a tampered event is dropped.
        val restored = GrantReceiver.restore(grant.signer, roomA, grant.device, receiver.snapshot())
        assertEquals(receiver.snapshot(), restored.snapshot())
        val tampered = Json.parseToJsonElement(receiver.snapshot().toString().replace(grant.grantId, "ab".repeat(16)))
        assertEquals(1, (GrantReceiver.restore(grant.signer, roomA, grant.device, tampered).snapshot()["events"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun `a revocation for this device's grant ends it and is carried`() {
        val good = case("good-grant").getValue("event")
        val grant = (parseGroupEvent(good) as GroupEventVerdict.Ok).parsed as ParsedGrant
        val secret = secretFor(grant.signer)!!
        val revocation = buildGrant(secret, grant.roomId, grant.device, grant.server, grant.scopes, grant.bytes?.toLong(), grant.grantId,
            grant.createdAt + 1, grant.expiration, "revoked", zeroAux)
        val receiver = GrantReceiver(grant.signer, grant.roomId, grant.device)
        receiver.offer(good)
        assertTrue(receiver.offer(revocation.toJson()))
        assertTrue(receiver.grantsFor(grant.server, grant.createdAt + 10).isEmpty())
        assertTrue(receiver.lockedOut(grant.server, grant.createdAt + 10))
        assertEquals(listOf(revocation.id), receiver.carry(grant.server, grant.createdAt + 10).map { it.event.id })
        receiver.acknowledge(grant.server, revocation.id)
        assertTrue(receiver.carry(grant.server, grant.createdAt + 10).isEmpty())
        // The keeper's own secret key signed it; the vector keys say so.
        assertEquals(grant.signer, dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(secret))
        assertTrue(secret.contentEquals(root.child("keys").child("authorityA").text("secret").hexToBytes()))
    }
}
