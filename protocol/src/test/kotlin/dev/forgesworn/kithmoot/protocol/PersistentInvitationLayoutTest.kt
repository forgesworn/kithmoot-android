package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Compare preflight with real encryption/signing and the independent web
 * fixture, including the padding jumps that a plaintext-only count misses. */
class PersistentInvitationLayoutTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/persistent-group-web.json")!!.readText()).jsonObject
    private val invitation = decodeInvitationUrl(fixture.getValue("url").jsonPrimitive.content)!!.invitation
    private val key = fixture.getValue("inviterKey").jsonPrimitive.content.hexToBytes()
    private val secret = fixture.getValue("secret").jsonPrimitive.content.hexToBytes()
    private val original = NostrEvent.fromJson(fixture.getValue("event"))
    private val nonce = fixture.getValue("nonce").jsonPrimitive.content.hexToBytes()

    private fun signed(ends: Long? = null, relays: List<String>? = null, destruct: Boolean = false,
        now: Long = original.createdAt) =
        encodePersistentInvitation(RoomInvitationHost(invitation, key), secret, now,
            nonce, ByteArray(32), ends, relays, destruct)

    private fun plaintext(event: NostrEvent): String {
        val conversation = Digests.hkdfSha256(invitation.bearer, null,
            "kithmoot/v3/group-invitation-key".toByteArray(Charsets.UTF_8), 32)
        return try { Nip44.decrypt(event.content, conversation) } finally { conversation.fill(0) }
    }

    private fun measure(label: String, ends: Long? = null, relays: List<String>? = null,
        destruct: Boolean = false, expectedPlaintext: Int? = null, now: Long = original.createdAt): Int {
        val event = signed(ends, relays, destruct, now)
        assertTrue(Events.verify(event))
        val admission = requireNotNull(decodePersistentInvitation(event, invitation))
        try {
            assertArrayEquals(secret, admission.secret)
            assertEquals(ends, admission.endsAt)
            assertEquals(relays, admission.relays)
            assertEquals(destruct, admission.destruct)
        } finally { admission.secret.fill(0) }
        val plainBytes = plaintext(event).toByteArray(Charsets.UTF_8).size
        expectedPlaintext?.let { assertEquals(it, plainBytes) }
        val bytes = event.toCompactJson().toByteArray(Charsets.UTF_8).size
        assertEquals(bytes, persistentInvitationEventBytes(now, ends, relays, destruct))
        println("INVITATION_LAYOUT case=$label plaintextBytes=$plainBytes encryptedBytes=${event.content.length} eventBytes=$bytes")
        return event.content.length
    }

    @Test fun `unsigned size agrees with the independent web envelope`() {
        assertEquals(original.toCompactJson().toByteArray(Charsets.UTF_8).size,
            persistentInvitationEventBytes(original.createdAt))
        val native = signed()
        assertEquals(original.content, native.content)
        assertEquals(original.id, native.id)
        measure("web")
    }

    @Test fun `all end destruct and relay policies agree with signed envelopes`() {
        for (timed in listOf(false, true)) for (destruct in listOf(false, true)) for (pinned in listOf(false, true)) {
            val ends = if (timed) original.createdAt + 3600 else null
            val relays = if (pinned) listOf("wss://fixture.invalid/", "ws://127.0.0.1:18777/") else null
            measure("policy-${if (timed) 1 else 0}-${if (destruct) 1 else 0}-${if (pinned) 1 else 0}", ends, relays, destruct)
        }
    }

    private fun relaysForPlaintextBytes(target: Int): List<String> {
        // Independent actual-body spelling, used only to choose URL lengths.
        fun bytes(urls: List<String>) = buildJsonObject {
            put("v", 3); put("room", deriveRoom(secret).roomId); put("secret", base64UrlEncode(secret))
            put("relays", JsonArray(urls.map(::JsonPrimitive)))
        }.toString().toByteArray(Charsets.UTF_8).size
        for (count in 1..MAX_INVITATION_RELAYS) {
            val urls = (0 until count).map { "wss://r$it.invalid/x" }.toMutableList()
            var extra = target - bytes(urls)
            if (extra < 0 || extra > urls.sumOf { 256 - it.length }) continue
            for (index in urls.indices) {
                val add = minOf(extra, 256 - urls[index].length)
                urls[index] += "a".repeat(add); extra -= add
            }
            assertEquals(0, extra)
            assertEquals(target, bytes(urls))
            assertEquals(urls, canonicalRoomRelays(urls))
            return urls
        }
        error("No supported relay list reaches $target plaintext bytes")
    }

    @Test fun `real NIP44 padding jumps are measured on both sides of fifteen boundaries`() {
        for (boundary in listOf(192, 224, 256, 320, 384, 448, 512, 640, 768, 896, 1024, 1280, 1536, 1792, 2048)) {
            val lengths = (boundary - 1..boundary + 1).map { bytes ->
                measure("padding-$bytes", relays = relaysForPlaintextBytes(bytes), expectedPlaintext = bytes)
            }
            assertEquals(lengths[0], lengths[1])
            assertTrue("Payload must grow after $boundary", lengths[2] > lengths[1])
        }
    }

    @Test fun `maximum supported relay and time spelling fits the real envelope`() {
        val relays = (0 until MAX_INVITATION_RELAYS).map { index ->
            val prefix = "wss://r$index.invalid/x"
            prefix + "a".repeat(256 - prefix.length)
        }
        val maxSafe = (1L shl 53) - 1
        measure("maximum", ends = maxSafe, relays = relays, destruct = true)
        assertEquals(persistentInvitationEventBytes(original.createdAt, maxSafe, relays, true),
            signed(maxSafe, relays, true).toCompactJson().toByteArray(Charsets.UTF_8).size)
    }

    @Test fun `native clock digit widths and latest supported timestamp match actual envelopes`() {
        val relays = (0 until MAX_INVITATION_RELAYS).map { index ->
            val prefix = "wss://r$index.invalid/x"
            prefix + "a".repeat(256 - prefix.length)
        }
        for (now in listOf(0L, 9L, 10L, 9_007_199_254_740_000L)) {
            measure("clock-$now", ends = (1L shl 53) - 1, relays = relays, destruct = true, now = now)
        }
    }

    @Test fun `preflight and fixed-entropy encoder refuse the same ended and malformed relay policies`() {
        for (ends in listOf(original.createdAt - 1, original.createdAt)) {
            assertThrows(IllegalArgumentException::class.java) { persistentInvitationEventBytes(original.createdAt, ends) }
            assertThrows(IllegalArgumentException::class.java) { signed(ends) }
        }
        for (relays in listOf(emptyList(), listOf("wss://fixture.invalid/", "wss://fixture.invalid/"),
            listOf("wss://FIXTURE.invalid/"), listOf("ws://remote.invalid/"),
            (0..MAX_INVITATION_RELAYS).map { "wss://r$it.invalid/" },
            listOf("wss://fixture.invalid/" + "a".repeat(256)))) {
            assertThrows(IllegalArgumentException::class.java) { persistentInvitationEventBytes(original.createdAt, relays = relays) }
            assertThrows(IllegalArgumentException::class.java) { signed(relays = relays) }
        }
    }
}
