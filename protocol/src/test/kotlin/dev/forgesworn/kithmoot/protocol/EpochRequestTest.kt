package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochRequestTest {
    private val now = 1_800_000_000L
    private val roomSecret = "07".repeat(32).hexToBytes()
    private val room = deriveRoom(roomSecret)
    private val authoritySecret = "08".repeat(32).hexToBytes()
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private val participantSecret = "09".repeat(32).hexToBytes()
    private val deviceSecret = "0a".repeat(32).hexToBytes()
    private val device = Schnorr.publicKeyHex(deviceSecret)
    private val credential = createDeviceCredential(participantSecret, device, room.roomId, now + 3_600, now, ByteArray(32))

    @Test fun `qualified epoch request retains the exact credential independent of reader mutation`() {
        val request = encodeEpochRequest(room.roomId, authority, room.roomKey, deviceSecret, credential, now)
        val checked = requireNotNull(decodeVerifiedEpochRequest(request, room.roomId, authoritySecret, room.roomKey, now))
        assertEquals(decodeEpochRequest(request, room.roomId, authoritySecret, room.roomKey, now), checked.request)
        assertEquals(now, checked.verifiedAt)
        assertEquals(credential.toCompactJson(), checked.credential().toCompactJson())
        val exported = checked.credential()
        @Suppress("UNCHECKED_CAST")
        val tag = exported.tags.first() as MutableList<String>
        tag[1] = "ff".repeat(32)
        assertFalse(Events.verify(exported))
        assertTrue(Events.verify(checked.credential()))
        assertEquals(credential.toCompactJson(), checked.credential().toCompactJson())
        // Changing a caller's outer request cannot alter retained evidence.
        @Suppress("UNCHECKED_CAST")
        val requestTag = request.tags.first() as MutableList<String>
        requestTag[1] = "ff".repeat(32)
        assertNull(decodeVerifiedEpochRequest(request, room.roomId, authoritySecret, room.roomKey, now))
        assertEquals(credential.toCompactJson(), checked.credential().toCompactJson())
    }

    @Test fun `enrolment evidence refuses invalid signatures credentials rooms devices admission and age`() {
        val valid = encodeEpochRequest(room.roomId, authority, room.roomKey, deviceSecret, credential, now)
        val expired = createDeviceCredential(participantSecret, device, room.roomId, now + 1, now)
        val elsewhere = createDeviceCredential(participantSecret, device, "ff".repeat(32), now + 3600, now)
        val refused = listOf(
            valid.copy(sig = "00".repeat(64)),
            valid.copy(tags = listOf(listOf("d", "ff".repeat(32)), listOf("p", authority))),
            encodeEpochRequest(room.roomId, authority, room.roomKey, deviceSecret, expired, now),
            encodeEpochRequest(room.roomId, authority, room.roomKey, deviceSecret, elsewhere, now),
            encodeEpochRequest(room.roomId, authority, room.roomKey, "0b".repeat(32).hexToBytes(), credential, now),
            encodeEpochRequest(room.roomId, authority, ByteArray(32) { 9 }, deviceSecret, credential, now),
        )
        for (request in refused) {
            assertNull(decodeVerifiedEpochRequest(request, room.roomId, authoritySecret, room.roomKey, now + 2))
            assertNull(decodeEpochRequest(request, room.roomId, authoritySecret, room.roomKey, now + 2))
        }
        assertNull(decodeVerifiedEpochRequest(valid, room.roomId, authoritySecret, room.roomKey, now + EPOCH_MAX_AGE_SECONDS + 1))
        assertNull(decodeVerifiedEpochRequest(valid, "ff".repeat(32), authoritySecret, room.roomKey, now))
        assertNull(decodeVerifiedEpochRequest(valid, room.roomId, authoritySecret, ByteArray(32) { 9 }, now))
        val excluded = RoomPolicy(KindredTier.OPEN, members = listOf("ff".repeat(32)))
        assertNull(decodeVerifiedEpochRequest(valid, room.roomId, authoritySecret, room.roomKey, now, excluded))
        assertNull(decodeEpochRequest(valid, room.roomId, authoritySecret, room.roomKey, now, excluded))
    }

    @Test fun `the authority creates a readable successor rekey and a closed rekey seals no secret`() {
        val current = deriveEpoch(RoomEpoch(0, roomSecret))
        val nextSecret = "0c".repeat(32).hexToBytes()
        val event = encodeRekeyEvent(
            room.roomId, authoritySecret, current, RoomEpoch(1, nextSecret),
            listOf(device), listOf("0d".repeat(32)), now,
            by = "0e".repeat(32),
            recipientNonces = mapOf(device to ByteArray(32) { 13 }),
            bodyNonce = ByteArray(32) { 14 }, auxRand = ByteArray(32) { 15 },
        )
        val decoded = decodeRekeyEvent(event, room.roomId, authority, current, deviceSecret)!!
        assertEquals(1, decoded.epoch)
        assertArrayEquals(nextSecret, decoded.secret)
        assertEquals(listOf("0d".repeat(32)), decoded.removed)
        assertEquals("0e".repeat(32), decoded.by)

        val closed = encodeRekeyEvent(
            room.roomId, authoritySecret, current, RoomEpoch(1, nextSecret),
            listOf(device), emptyList(), now, closed = true,
            recipientNonces = mapOf(device to ByteArray(32) { 16 }),
            bodyNonce = ByteArray(32) { 17 }, auxRand = ByteArray(32) { 18 },
        )
        val terminal = decodeRekeyEvent(closed, room.roomId, authority, current, deviceSecret)!!
        assertTrue(terminal.closed)
        assertNull(terminal.secret)
    }

    @Test fun `a credential-bound device asks the authority and malformed or stale requests fail closed`() {
        val request = encodeEpochRequest(
            room.roomId, authority, room.roomKey, deviceSecret, credential, now,
            nonce = ByteArray(32) { 1 }, auxRand = ByteArray(32) { 2 },
        )
        val decoded = decodeEpochRequest(request, room.roomId, authoritySecret, room.roomKey, now)
        assertEquals(device, decoded?.device)
        assertEquals(credential.pubkey, decoded?.participant)
        assertEquals(request.id, decoded?.request)
        assertNull(decodeEpochRequest(request, room.roomId, authoritySecret, room.roomKey, now + EPOCH_MAX_AGE_SECONDS + 1))
        assertNull(decodeEpochRequest(request.copy(tags = listOf(listOf("d", "ff".repeat(32)), listOf("p", authority))), room.roomId, authoritySecret, room.roomKey, now))

        val borrowed = encodeEpochRequest(
            room.roomId, authority, room.roomKey, "0b".repeat(32).hexToBytes(), credential, now,
            nonce = ByteArray(32) { 3 }, auxRand = ByteArray(32) { 4 },
        )
        assertNull(decodeEpochRequest(borrowed, room.roomId, authoritySecret, room.roomKey, now))
    }

    @Test fun `a request proves admission under the room key and a stranger's request is refused`() {
        val request = encodeEpochRequest(
            room.roomId, authority, room.roomKey, deviceSecret, credential, now,
            nonce = ByteArray(32) { 21 }, auxRand = ByteArray(32) { 22 },
        )
        val expected = epochRequestAdmission(room.roomKey, room.roomId, authority, device, now)
        val body = Json.parseToJsonElement(
            Nip44.decrypt(request.content, Nip44.conversationKey(authoritySecret, device.hexToBytes())),
        ).jsonObject
        assertEquals(expected, body["admission"]?.jsonPrimitive?.content)
        assertTrue(expected != epochRequestAdmission(room.roomKey, room.roomId, authority, device, now + 1))

        // A desk holding another room key cannot verify it, and a proof made
        // under another key - a stranger guessing, or a device that used the
        // current epoch's key instead of epoch 0's - is refused by this desk.
        val otherKey = ByteArray(32) { 9 }
        assertNull(decodeEpochRequest(request, room.roomId, authoritySecret, otherKey, now))
        val strangers = encodeEpochRequest(
            room.roomId, authority, otherKey, deviceSecret, credential, now,
            nonce = ByteArray(32) { 23 }, auxRand = ByteArray(32) { 24 },
        )
        assertNull(decodeEpochRequest(strangers, room.roomId, authoritySecret, room.roomKey, now))

        // A request from before the proof existed carries no admission and is refused.
        val bare = buildJsonObject {
            put("v", 1)
            put("credential", credential.toJson())
        }
        val conversation = Nip44.conversationKey(deviceSecret, authority.hexToBytes())
        val stripped = Events.sign(
            deviceSecret, KIND_EPOCH_REQUEST, now, listOf(listOf("d", room.roomId), listOf("p", authority)),
            Nip44.encrypt(bare.toString(), conversation, ByteArray(32) { 25 }), ByteArray(32) { 26 },
        )
        assertNull(decodeEpochRequest(stripped, room.roomId, authoritySecret, room.roomKey, now))
    }

    @Test fun `the authority grants the current epoch or returns a terminal refusal to this request only`() {
        val request = encodeEpochRequest(
            room.roomId, authority, room.roomKey, deviceSecret, credential, now,
            nonce = ByteArray(32) { 5 }, auxRand = ByteArray(32) { 6 },
        )
        val nextSecret = "0c".repeat(32).hexToBytes()
        val removed = listOf("0d".repeat(32), "0d".repeat(32), "0e".repeat(32))
        val grant = encodeEpochGrant(
            room.roomId, authoritySecret, device, request.id, now,
            RoomEpoch(2, nextSecret), removed,
            nonce = ByteArray(32) { 7 }, auxRand = ByteArray(32) { 8 },
        )
        val decoded = decodeEpochGrant(grant, room.roomId, authority, deviceSecret, request.id, now) as EpochGrant.Current
        assertEquals(2, decoded.epoch)
        assertArrayEquals(nextSecret, decoded.secret)
        assertEquals(removed.distinct().sorted(), decoded.removed)
        assertNull(decodeEpochGrant(grant, room.roomId, authority, deviceSecret, "ff".repeat(32), now))

        val refusal = encodeEpochGrant(
            room.roomId, authoritySecret, device, request.id, now,
            refused = "removed", nonce = ByteArray(32) { 9 }, auxRand = ByteArray(32) { 10 },
        )
        assertEquals(EpochGrant.Refused("removed"), decodeEpochGrant(refusal, room.roomId, authority, deviceSecret, request.id, now))

        val unchanged = encodeEpochGrant(
            room.roomId, authoritySecret, device, request.id, now,
            epoch = RoomEpoch(0, roomSecret), nonce = ByteArray(32) { 11 }, auxRand = ByteArray(32) { 12 },
        )
        val zero = decodeEpochGrant(unchanged, room.roomId, authority, deviceSecret, request.id, now) as EpochGrant.Current
        assertEquals(0, zero.epoch)
        assertNull(zero.secret)
        assertTrue(zero.removed.isEmpty())
    }

    @Test fun `a scheduled rekey reads as scheduled and is never written beside a removal or a close`() {
        val current = deriveEpoch(RoomEpoch(0, roomSecret))
        val next = RoomEpoch(1, "0c".repeat(32).hexToBytes())
        val event = encodeRekeyEvent(
            room.roomId, authoritySecret, current, next, listOf(device), emptyList(), now, commit = true, scheduled = true,
        )
        val decoded = decodeRekeyEvent(event, room.roomId, authority, current, deviceSecret)!!
        assertTrue(decoded.scheduled)
        assertArrayEquals(next.secret, decoded.secret)
        assertTrue(readRekeyEvidence(event, room.roomId, authority, 0, current.key)!!.scheduled)
        val body = Json.parseToJsonElement(Nip44.decrypt(event.content, current.key)).jsonObject
        assertEquals(listOf("v", "epoch", "removed", "scheduled", "commit", "keys"), body.keys.toList())
        // On the wire nothing but the body says so.
        assertEquals(listOf(listOf("d", room.roomId), listOf("epoch", "1")), event.tags)

        val plain = encodeRekeyEvent(room.roomId, authoritySecret, current, next, listOf(device), emptyList(), now)
        assertFalse(decodeRekeyEvent(plain, room.roomId, authority, current, deviceSecret)!!.scheduled)
        assertThrows(IllegalArgumentException::class.java) {
            encodeRekeyEvent(room.roomId, authoritySecret, current, next, listOf(device), listOf("0d".repeat(32)), now, scheduled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeRekeyEvent(room.roomId, authoritySecret, current, next, listOf(device), emptyList(), now, closed = true, scheduled = true)
        }
    }

    @Test fun `a grant carries the window's passed epochs oldest first`() {
        val request = encodeEpochRequest(room.roomId, authority, room.roomKey, deviceSecret, credential, now)
        val passed = (2..4).map { LeftEpoch(it, ByteArray(32) { n -> (it * 16 + n).toByte() }, now - 86_400L * (5 - it)) }
        val grant = encodeEpochGrant(
            room.roomId, authoritySecret, device, request.id, now, RoomEpoch(5, "0c".repeat(32).hexToBytes()),
            passed = passed.reversed(),
        )
        val decoded = decodeEpochGrant(grant, room.roomId, authority, deviceSecret, request.id, now) as EpochGrant.Current
        assertEquals(5, decoded.epoch)
        assertEquals(listOf(2, 3, 4), decoded.passed.map { it.epoch })
        assertEquals(passed.map { it.leftAt }, decoded.passed.map { it.leftAt })
        passed.zip(decoded.passed).forEach { (sent, read) -> assertArrayEquals(sent.secret, read.secret) }

        // Without the window the body is as before.
        val bare = encodeEpochGrant(room.roomId, authoritySecret, device, request.id, now, RoomEpoch(5, "0c".repeat(32).hexToBytes()))
        val conversation = Nip44.conversationKey(deviceSecret, authority.hexToBytes())
        assertFalse("passed" in Json.parseToJsonElement(Nip44.decrypt(bare.content, conversation)).jsonObject)
        assertTrue((decodeEpochGrant(bare, room.roomId, authority, deviceSecret, request.id, now) as EpochGrant.Current).passed.isEmpty())

        val epoch = RoomEpoch(5, "0c".repeat(32).hexToBytes())
        fun refuses(list: List<LeftEpoch>) = assertThrows(IllegalArgumentException::class.java) {
            encodeEpochGrant(room.roomId, authoritySecret, device, request.id, now, epoch, passed = list)
        }
        refuses(listOf(LeftEpoch(0, ByteArray(32), now)))
        refuses(listOf(LeftEpoch(5, ByteArray(32), now)))
        refuses(listOf(LeftEpoch(2, ByteArray(32), now), LeftEpoch(2, ByteArray(32), now)))
        refuses(listOf(LeftEpoch(2, ByteArray(32), -1)))
        refuses((1..17).map { LeftEpoch(it, ByteArray(32), now) })
    }

    @Test fun `a malformed passed list costs the history and keeps the grant`() {
        val request = encodeEpochRequest(room.roomId, authority, room.roomKey, deviceSecret, credential, now)
        val secret = "0c".repeat(32)
        val good = """{"epoch":2,"secret":"${"A".repeat(43)}","left":$now}"""
        val malformed = listOf(
            "\"not a list\"",
            "[]",
            """[{"epoch":"2","secret":"${"A".repeat(43)}","left":$now}]""",
            """[{"epoch":2,"secret":"${"A".repeat(43)}=","left":$now}]""",
            """[{"epoch":2,"secret":"${"A".repeat(42)}","left":$now}]""",
            """[{"epoch":2,"secret":"${"A".repeat(43)}","left":-1}]""",
            """[{"epoch":2,"secret":"${"A".repeat(43)}","left":1.5}]""",
            """[{"epoch":0,"secret":"${"A".repeat(43)}","left":$now}]""",
            """[{"epoch":5,"secret":"${"A".repeat(43)}","left":$now}]""",
            """[$good,$good]""",
            """[{"epoch":3,"secret":"${"A".repeat(43)}","left":$now},$good]""",
            "[" + (1..4).joinToString(",") { """{"epoch":$it,"secret":"${"A".repeat(43)}","left":$now}""" } + ",1]",
        )
        val conversation = Nip44.conversationKey(authoritySecret, device.hexToBytes())
        fun grantWith(passed: String): NostrEvent {
            val body = """{"v":1,"request":"${request.id}","epoch":5,"secret":"${base64UrlEncode(secret.hexToBytes())}","removed":[],"passed":$passed}"""
            return Events.sign(authoritySecret, KIND_EPOCH_GRANT, now, listOf(listOf("d", room.roomId), listOf("p", device)), Nip44.encrypt(body, conversation))
        }
        for (passed in malformed) {
            val decoded = decodeEpochGrant(grantWith(passed), room.roomId, authority, deviceSecret, request.id, now) as? EpochGrant.Current
            assertEquals(passed, 5, decoded?.epoch)
            assertArrayEquals(passed, secret.hexToBytes(), decoded?.secret)
            assertTrue(passed, decoded!!.passed.isEmpty())
        }
        val one = decodeEpochGrant(grantWith("[$good]"), room.roomId, authority, deviceSecret, request.id, now) as EpochGrant.Current
        assertEquals(listOf(2), one.passed.map { it.epoch })
    }

    @Test fun `the history window keeps thirty days, newest first, at most sixteen`() {
        val left = (1..20).map { it to now - 3_600L * (21 - it) } + listOf(0 to now - HISTORY_WINDOW_SECONDS - 1)
        val kept = epochsInWindow(left, now, { it.first }, { it.second })
        assertEquals((20 downTo 5).toList(), kept.map { it.first })
        assertEquals(listOf(2), epochsInWindow(listOf(2 to now - HISTORY_WINDOW_SECONDS, 1 to now - HISTORY_WINDOW_SECONDS - 1), now, { it.first }, { it.second }).map { it.first })
    }
}
