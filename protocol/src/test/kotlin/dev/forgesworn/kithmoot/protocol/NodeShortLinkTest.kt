package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NodeShortLinkTest {
    private val now = 1_900_000_000L
    private val base = "https://kithmoot.app/j/"
    private val authority = Entropy.bytes(32)
    private val room = deriveRoom(Entropy.bytes(32)).roomId
    private val service = Schnorr.publicKeyHex(Entropy.bytes(32))
    private val ports = NodeTurnPorts(34790, 53499)
    private val policy = buildJsonObject {
        put("tier", "open"); put("members", JsonArray(listOf(Schnorr.publicKeyHex(Entropy.bytes(32)), Schnorr.publicKeyHex(Entropy.bytes(32))).map(::JsonPrimitive))); put("quiet", true)
    }
    private fun sign(endsAt: Long? = null, turnPorts: NodeTurnPorts = NodeTurnPorts.DEFAULT, existing: NodeExistingRoomPolicy? = null) =
        NodeRooms.sign(authority, room, "https://demo.forgesworn.dev", service, now, endsAt, turnPorts = turnPorts, existing = existing).toJson()
    private val records = mapOf(
        "plain" to sign(), "ends" to sign(now + 86400), "ports" to sign(turnPorts = ports),
        "existing" to sign(existing = NodeExistingRoomPolicy(false)), "destruct" to sign(now + 3600, existing = NodeExistingRoomPolicy(true)),
        "everything" to sign(now + 30 * 86400, ports, NodeExistingRoomPolicy(true, policy)),
    )
    private fun fragment(link: String) = Base64.getUrlDecoder().decode(link.substringAfter('#'))
    private fun withFragment(link: String, bytes: ByteArray) = link.substringBefore('#') + "#" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test fun rebuildsTheIdenticalSignedRecordOfEveryShape() {
        for ((name, event) in records) {
            val short = NodeRooms.encodeShortLink(base, event, now); val long = NodeRooms.encodeLink(base, event, now)
            assertEquals(name, SHORT_NODE_LINK_VERSION, fragment(short)[0].toInt())
            assertEquals(name, requireNotNull(NodeRooms.read(event, now)).event, requireNotNull(NodeRooms.parseLink(short, now)).event)
            assertTrue(name, short.length < long.length / 2)
            assertTrue(name, runCatching { decodeJoinUrl(short) }.isFailure)
        }
        assertTrue(NodeRooms.encodeShortLink(base, records.getValue("plain"), now).length < 300)
        assertTrue(NodeRooms.encodeShortLink(base, records.getValue("ends"), now).length < 300)
    }

    @Test fun refusesEverySingleBitChangeATruncationAndATrailingByte() {
        val link = NodeRooms.encodeShortLink(base, records.getValue("everything"), now); val bytes = fragment(link)
        for (i in bytes.indices) for (bit in intArrayOf(1, 128)) {
            val changed = bytes.copyOf(); changed[i] = (changed[i].toInt() xor bit).toByte()
            assertNull("byte $i bit $bit", NodeRooms.inspectLink(withFragment(link, changed), now))
        }
        assertNull(NodeRooms.parseLink(withFragment(link, bytes.copyOf(bytes.size - 1)), now))
        assertNull(NodeRooms.parseLink(withFragment(link, bytes + 0.toByte()), now))
    }

    @Test fun refusesUnknownFlagsAndExistingRoomFlagsOnANewRoomRecord() {
        val bytes = fragment(NodeRooms.encodeShortLink(base, records.getValue("plain"), now))
        for (flag in intArrayOf(8, 16, 32, 128)) {
            val changed = bytes.copyOf(); changed[1] = (changed[1].toInt() or flag).toByte()
            assertNull("flag $flag", NodeRooms.parseLink(withFragment("$base#x", changed), now))
        }
    }

    @Test fun keepsTheLongLinkForARecordSignedWithOtherJsonSpelling() {
        val plain = requireNotNull(NodeRooms.read(records.getValue("plain"), now)).event
        val body = Json.parseToJsonElement(plain.content).jsonObject
        val reordered = buildJsonObject { body.filterKeys { it != "v" }.forEach { (k, v) -> put(k, v) }; put("v", body.getValue("v")) }.toString()
        val event = Events.sign(authority, NODE_RECORD_KIND, now, plain.tags, reordered).toJson()
        assertNotNull(NodeRooms.read(event, now))
        assertEquals(NodeRooms.encodeLink(base, event, now), NodeRooms.encodeShortLink(base, event, now))
    }

    @Test fun turnsAShortLinkIntoTheLongFormForStorageAndLeavesALongLinkAlone() {
        val event = records.getValue("everything")
        val short = NodeRooms.encodeShortLink(base, event, now); val long = NodeRooms.encodeLink(base, event, now)
        val stored = requireNotNull(NodeRooms.longLink(short, now))
        assertNotEquals(SHORT_NODE_LINK_VERSION, fragment(stored)[0].toInt())
        assertEquals(long, stored)
        assertEquals(long, NodeRooms.longLink(long, now))
        assertNull(NodeRooms.longLink(short, now + 31 * 86400))
        assertNull(NodeRooms.longLink("$base#AAAA", now))
    }

    @Test fun tellsAnEndedOrEarlyLinkFromABrokenOne() {
        val event = records.getValue("ends"); val endsAt = now + 86400
        for (link in listOf(NodeRooms.encodeShortLink(base, event, now), NodeRooms.encodeLink(base, event, now))) {
            assertEquals(NodeRoomLinkState.Open(requireNotNull(NodeRooms.read(event, now))), NodeRooms.inspectLink(link, now))
            assertTrue(NodeRooms.inspectLink(link, endsAt - 1) is NodeRoomLinkState.Open)
            assertEquals(NodeRoomLinkState.Ended(endsAt), NodeRooms.inspectLink(link, endsAt))
            assertEquals(NodeRoomLinkState.Ended(endsAt), NodeRooms.inspectLink(link, endsAt + 365 * 86400))
            assertTrue(NodeRooms.inspectLink(link, now - 30) is NodeRoomLinkState.Open)
            assertEquals(NodeRoomLinkState.Early(now), NodeRooms.inspectLink(link, now - 31))
        }
        val plain = NodeRooms.encodeShortLink(base, records.getValue("plain"), now)
        assertTrue(NodeRooms.inspectLink(plain, now + 10 * 365 * 86400) is NodeRoomLinkState.Open)
    }

    @Test fun givesNoStateForAForgedUnparseableOrLegacyLink() {
        val genuine = requireNotNull(NodeRooms.read(records.getValue("ends"), now)).event
        val forged = Events.sign(Entropy.bytes(32), genuine.kind, genuine.createdAt, genuine.tags, genuine.content).copy(pubkey = genuine.pubkey)
        val forgedLink = base + "#" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            buildJsonObject { put("v", NODE_LINK_VERSION); put("node", forged.toJson()) }.toString().toByteArray())
        assertNull(NodeRooms.inspectLink(forgedLink, now + 86400))
        assertNull(NodeRooms.inspectLink("$base#", now))
        assertNull(NodeRooms.inspectLink("$base#AAAA", now))
        assertNull(NodeRooms.inspectLink(base + "#" + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"v":2,"j":"x","h":"y"}""".toByteArray()), now))
        assertNull(NodeRooms.inspectLink("not a url", now))
    }
}
