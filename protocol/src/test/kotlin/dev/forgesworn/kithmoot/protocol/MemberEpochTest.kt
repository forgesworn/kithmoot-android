package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The refusal rules of fold-kit's `decodeMemberEpochGrant` and `decodeMemberEpochRequest`, one by one. */
class MemberEpochTest {
    private val now = 1_800_000_000L
    private val roomSecret = "07".repeat(32).hexToBytes()
    private val room = deriveRoom(roomSecret)
    private val authoritySecret = "08".repeat(32).hexToBytes()
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private val participantSecret = "09".repeat(32).hexToBytes()
    private val participant = Schnorr.publicKeyHex(participantSecret)
    private val deviceSecret = "0a".repeat(32).hexToBytes()
    private val device = Schnorr.publicKeyHex(deviceSecret)
    private val memberDevice = Schnorr.publicKeyHex("0b".repeat(32).hexToBytes())
    private val credential = createDeviceCredential(participantSecret, device, room.roomId, now + 3_600, now, ByteArray(32))
    private val epoch0 = deriveEpoch(RoomEpoch(0, roomSecret))
    private val secrets = (1..4).map { n -> RoomEpoch(n, ByteArray(32) { (40 + n).toByte() }) }
    private val request = "1f".repeat(32)

    /** Rekeys 1..4, each under the key before it; [commitAt] chooses which carry a commitment. */
    private fun chain(
        commitAt: Set<Int> = setOf(1, 2, 3, 4),
        removedAt: Map<Int, List<String>> = emptyMap(),
        closedAt: Int? = null,
        signer: ByteArray = authoritySecret,
    ): List<NostrEvent> {
        var previous = epoch0
        return secrets.map { next ->
            encodeRekeyEvent(
                room.roomId, signer, previous, next, listOf(memberDevice), removedAt[next.epoch].orEmpty(), now - 10,
                closed = closedAt == next.epoch, commit = next.epoch in commitAt,
            ).also { previous = deriveEpoch(next) }
        }
    }

    private fun grant(
        epochs: List<RoomEpoch>,
        rekeys: List<NostrEvent>,
        to: String = device,
        at: Long = now,
        requestId: String = request,
    ) = encodeMemberEpochGrant(room.roomId, to, requestId, epochs, rekeys, at)

    private fun decode(
        event: NostrEvent,
        from: EpochKeys = epoch0,
        requests: Set<String> = setOf(request),
        expected: Int? = null,
        removed: List<String> = emptyList(),
        at: Long = now,
    ) = decodeMemberEpochGrant(
        event, room.roomId, authority, deviceSecret, requests, from.epoch, from.key, participant, at, removed, expected,
    )

    /** A grant with a hand-written body, sealed and signed as a one-time key would. */
    private fun rawGrant(body: JsonObject, tags: List<List<String>> = listOf(listOf("d", room.roomId), listOf("p", device))): NostrEvent {
        val signer = secretKeyFromSeed(ByteArray(48) { 3 })
        val key = Nip44.conversationKey(signer, device.hexToBytes())
        return Events.sign(signer, KIND_MEMBER_EPOCH_GRANT, now, tags, Nip44.encrypt(body.toString(), key))
    }

    private fun plain(event: NostrEvent) = event.toJson()

    @Test fun `the labels and kinds are the ones fold-kit freezes`() {
        assertEquals(20_471, KIND_MEMBER_EPOCH_REQUEST)
        assertEquals(20_472, KIND_MEMBER_EPOCH_GRANT)
        assertEquals("kithmoot/v1/member-epoch-request-key", MEMBER_EPOCH_REQUEST_KEY_INFO)
        assertEquals("kithmoot/v1/epoch-commit:", EPOCH_COMMIT_PREFIX)
        assertEquals(32, MAX_MEMBER_EPOCH_CHAIN)
        assertEquals(60_000, MAX_MEMBER_GRANT_BYTES)
    }

    @Test fun `a full chain from epoch 0 is accepted, and from the middle too`() {
        val rekeys = chain(removedAt = mapOf(2 to listOf("0d".repeat(32))))
        val accepted = decode(grant(secrets, rekeys), removed = listOf("0e".repeat(32)))
        assertNotNull(accepted)
        assertEquals(4, accepted!!.epoch.epoch)
        assertArrayEquals(secrets[3].secret, accepted.epoch.secret)
        assertEquals(listOf("0d".repeat(32), "0e".repeat(32)), accepted.removed)
        assertEquals((1..4).toList(), accepted.chain.map { it.epoch })
        assertEquals(rekeys.map { it.id }, accepted.rekeys.map { it.id })

        val fromTwo = decode(grant(secrets.drop(2), rekeys.drop(2)), from = deriveEpoch(secrets[1]))
        assertEquals(4, fromTwo?.epoch?.epoch)
    }

    @Test fun `a legacy rekey in the middle is vouched for by its successor, a legacy top is refused`() {
        assertNotNull(decode(grant(secrets, chain(commitAt = setOf(4)))))
        assertNull(decode(grant(secrets, chain(commitAt = setOf(1, 2, 3)))))
    }

    @Test fun `forged secrets anywhere in the chain are refused`() {
        val rekeys = chain()
        for (i in secrets.indices) {
            val forged = secrets.mapIndexed { n, e -> if (n == i) RoomEpoch(e.epoch, ByteArray(32) { 99 }) else e }
            assertNull("forged at ${i + 1}", decode(grant(forged, rekeys)))
        }
    }

    @Test fun `a chain that closes the room or removes the requester is refused`() {
        assertNull(decode(grant(secrets, chain(closedAt = 3))))
        assertNull(decode(grant(secrets, chain(removedAt = mapOf(2 to listOf(participant))))))
    }

    @Test fun `a rekey not signed by the authority, or out of order, is refused`() {
        assertNull(decode(grant(secrets, chain(signer = "0c".repeat(32).hexToBytes()))))
        val rekeys = chain()
        assertNull(decode(grant(secrets, listOf(rekeys[1], rekeys[0], rekeys[2], rekeys[3]))))
    }

    @Test fun `a grant short of the rollback floor is refused, one reaching it is accepted`() {
        val rekeys = chain()
        assertNull(decode(grant(secrets.take(3), rekeys.take(3)), expected = 4))
        assertNotNull(decode(grant(secrets, rekeys), expected = 4))
        assertNotNull(decode(grant(secrets.take(3), rekeys.take(3)), expected = 3))
    }

    @Test fun `the envelope must be fresh, for this room, to this device and for an outstanding request`() {
        val rekeys = chain()
        val good = grant(secrets, rekeys)
        assertNotNull(decode(good))
        assertNull(decode(good, at = now + 91))
        assertNull(decode(good, at = now - 91))
        assertNull(decode(good, requests = setOf("2f".repeat(32))))
        assertNull(decode(good, requests = emptySet()))
        assertNull(decode(grant(secrets, rekeys, to = memberDevice)))
        assertNull(decode(good.copy(sig = "00".repeat(64))))
        assertNull(decode(good.copy(kind = KIND_EPOCH_GRANT)))
        // A desk-side starting point that does not match where the device is.
        assertNull(decode(good, from = deriveEpoch(secrets[0])))
        val elsewhere = encodeMemberEpochGrant("33".repeat(32), device, request, secrets, rekeys, now)
        assertNull(decode(elsewhere))
    }

    @Test fun `malformed bodies are refused`() {
        val rekeys = chain()
        fun body(
            epoch: Int = 4,
            secretsJson: JsonArray = buildJsonArray { secrets.forEach { add(JsonPrimitive(base64UrlEncode(it.secret))) } },
            rekeysJson: JsonArray = JsonArray(rekeys.map(::plain)),
            v: Int = 1,
            req: String = request,
        ) = buildJsonObject {
            put("v", v); put("request", req); put("epoch", epoch); put("secrets", secretsJson); put("rekeys", rekeysJson)
        }
        assertNotNull(decode(rawGrant(body())))
        assertNull(decode(rawGrant(body(v = 2))))
        assertNull(decode(rawGrant(body(epoch = 3))))
        assertNull(decode(rawGrant(body(epoch = 5))))
        assertNull(decode(rawGrant(body(req = "zz"))))
        assertNull(decode(rawGrant(body(secretsJson = JsonArray(emptyList()), rekeysJson = JsonArray(emptyList()), epoch = 0))))
        assertNull(decode(rawGrant(body(rekeysJson = JsonArray(rekeys.take(3).map(::plain))))))
        val padded = buildJsonArray { secrets.forEach { add(JsonPrimitive(base64UrlEncode(it.secret) + "=")) } }
        assertNull(decode(rawGrant(body(secretsJson = padded))))
        val short = buildJsonArray { secrets.forEach { add(JsonPrimitive(base64UrlEncode(it.secret.copyOf(31)))) } }
        assertNull(decode(rawGrant(body(secretsJson = short))))
        assertNull(decode(rawGrant(body(), tags = listOf(listOf("d", room.roomId)))))
    }

    @Test fun `a chain longer than 32 epochs is never encoded or accepted`() {
        val many = (1..33).map { RoomEpoch(it, ByteArray(32) { 1 }) }
        try {
            encodeMemberEpochGrant(room.roomId, device, request, many, List(33) { chain()[0] }, now)
            error("encoded a 33-epoch grant")
        } catch (_: IllegalArgumentException) {}
        val body = buildJsonObject {
            put("v", 1); put("request", request); put("epoch", 33)
            put("secrets", buildJsonArray { many.forEach { add(JsonPrimitive(base64UrlEncode(it.secret))) } })
            put("rekeys", JsonArray(List(33) { plain(chain()[0]) }))
        }
        assertNull(decode(rawGrant(body)))
    }

    @Test fun `the grant is signed by a one-time key, never the same twice`() {
        val rekeys = chain()
        val a = grant(secrets, rekeys)
        val b = grant(secrets, rekeys)
        assertNotEquals(a.pubkey, b.pubkey)
        assertNotEquals(memberDevice, a.pubkey)
        assertEquals(listOf(listOf("d", room.roomId), listOf("p", device)), a.tags)
        val ends = encodeMemberEpochGrant(room.roomId, device, request, secrets, rekeys, now, roomEnds = now + 60)
        assertEquals(listOf("expiration", (now + 60).toString()), ends.tags.last())
    }

    @Test fun `a member request decodes for a member and fails closed for everybody else`() {
        val event = encodeMemberEpochRequest(room.roomId, authority, room.roomKey, deviceSecret, credential, 2, now)
        assertEquals(listOf(listOf("d", room.roomId)), event.tags)
        assertEquals(MemberEpochRequest(device, participant, event.id, 2), decodeMemberEpochRequest(event, room.roomId, authority, room.roomKey, now))
        assertNull(decodeMemberEpochRequest(event, room.roomId, authority, room.roomKey, now + 91))
        assertNull(decodeMemberEpochRequest(event, room.roomId, authority, ByteArray(32) { 5 }, now))
        assertNull(decodeMemberEpochRequest(event, room.roomId, memberDevice, room.roomKey, now))
        assertNull(decodeMemberEpochRequest(event, "33".repeat(32), authority, room.roomKey, now))
        assertNull(decodeMemberEpochRequest(event.copy(sig = "00".repeat(64)), room.roomId, authority, room.roomKey, now))
        // A credential for another device than the one that signed.
        val other = encodeMemberEpochRequest(room.roomId, authority, room.roomKey, "0c".repeat(32).hexToBytes(), credential, 0, now)
        assertNull(decodeMemberEpochRequest(other, room.roomId, authority, room.roomKey, now))
        // A body with no admission proof.
        val key = deriveMemberEpochRequestKey(room.roomKey)
        val bare = Events.sign(deviceSecret, KIND_MEMBER_EPOCH_REQUEST, now, listOf(listOf("d", room.roomId)),
            Nip44.encrypt(buildJsonObject { put("v", 1); put("credential", credential.toJson()); put("have", 0) }.toString(), key))
        assertNull(decodeMemberEpochRequest(bare, room.roomId, authority, room.roomKey, now))
    }

    @Test fun `the commitment is bound to the room, the epoch and the secret`() {
        val c = epochCommitment(room.roomId, 2, secrets[1].secret)
        assertEquals(c, epochCommitment(room.roomId.uppercase(), 2, secrets[1].secret))
        assertNotEquals(c, epochCommitment(room.roomId, 3, secrets[1].secret))
        assertNotEquals(c, epochCommitment("33".repeat(32), 2, secrets[1].secret))
        assertNotEquals(c, epochCommitment(room.roomId, 2, secrets[2].secret))
        try { epochCommitment(room.roomId, 0, secrets[0].secret); error("epoch 0 has no commitment") } catch (_: IllegalArgumentException) {}
        val notice = decodeRekeyEvent(chain()[0], room.roomId, authority, epoch0, "0b".repeat(32).hexToBytes())
        assertEquals(epochCommitment(room.roomId, 1, secrets[0].secret), notice?.commit)
    }
}
