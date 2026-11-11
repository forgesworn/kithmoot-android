package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who a bell's own credential says rang it. A phone with the app closed has
 * no roster, so this is the only way it can tell its own person's answer
 * from somebody else's. Everything short of a credential for this room, at
 * the reader's clock, naming the signing device, attributes nothing; none of
 * it costs the bell itself.
 */
class CallBellAttributionTest {
    private val roomId = "ab".repeat(32)
    private val otherRoomId = "cd".repeat(32)
    private val roomKey = ByteArray(32) { 3 }
    private val participantSk = ByteArray(32) { 1 }
    private val participant = Schnorr.publicKeyHex(participantSk)
    private val deviceSk = ByteArray(32) { 2 }
    private val device = Schnorr.publicKeyHex(deviceSk)
    private val otherDevice = Schnorr.publicKeyHex(ByteArray(32) { 4 })
    private val now = 1_800_000_000L
    private val call = CallMembership("c0ffee00000000000000000000000000", now)

    private fun credential(room: String = roomId, forDevice: String = device, expiresAt: Long = now + 3_600) =
        createDeviceCredential(participantSk, forDevice, room, expiresAt, createdAt = now - 10_000)

    private fun bell(state: CallBellState = CallBellState.ANSWER, with: NostrEvent? = credential(), at: Long = now, since: Long = call.since) =
        encodeCallBellEvent(EncodeCallBellOptions(roomId, roomKey, deviceSk, state, call.copy(since = since), at, credential = with))

    @Test fun `an answer carrying its own credential names its participant`() {
        val decoded = requireNotNull(decodeCallBellEvent(bell(), roomId, roomKey, now))
        assertEquals(CallBellState.ANSWER, decoded.state)
        assertEquals(participant, decoded.participant)
    }

    @Test fun `a person-scoped credential attributes too, as the roster accepts it`() {
        val person = createPersonCredential(participantSk, device, expiresAt = now + 3_600, createdAt = now - 10_000)
        assertEquals(participant, decodeCallBellEvent(bell(with = person), roomId, roomKey, now)?.participant)
    }

    @Test fun `a credential for another device attributes nobody but the bell still stands`() {
        val decoded = requireNotNull(decodeCallBellEvent(bell(with = credential(forDevice = otherDevice)), roomId, roomKey, now))
        assertNull(decoded.participant)
    }

    @Test fun `a credential for another room attributes nobody but the bell still stands`() {
        val decoded = requireNotNull(decodeCallBellEvent(bell(with = credential(room = otherRoomId)), roomId, roomKey, now))
        assertNull(decoded.participant)
    }

    @Test fun `a credential is judged at the reader's clock, not the bell's`() {
        // Good when the bell was stamped, lapsed by the time it is read: a
        // replay carries a credential that has run out, so it names nobody.
        val event = bell(with = credential(expiresAt = now + 30), at = now)
        assertEquals(participant, decodeCallBellEvent(event, roomId, roomKey, now)?.participant)
        val late = requireNotNull(decodeCallBellEvent(event, roomId, roomKey, now + 60))
        assertNull(late.participant)
    }

    @Test fun `a credential with a broken signature attributes nobody`() {
        val forged = credential().let { it.copy(sig = it.sig.replaceRange(0, 2, if (it.sig.startsWith("00")) "11" else "00")) }
        val decoded = requireNotNull(decodeCallBellEvent(bell(with = forged), roomId, roomKey, now))
        assertNull(decoded.participant)
    }

    @Test fun `a credential that is not an event at all leaves the bell valid`() {
        for (junk in listOf(JsonPrimitive("not a credential"), buildJsonObject { put("kind", 20460) })) {
            val decoded = requireNotNull(decodeCallBellEvent(rawBell { put("credential", junk) }, roomId, roomKey, now)) { "bell with $junk" }
            assertNull(decoded.participant)
        }
    }

    @Test fun `a bell without a credential, as every older sender rings, names nobody`() {
        assertNull(requireNotNull(decodeCallBellEvent(bell(with = null), roomId, roomKey, now)).participant)
    }

    @Test fun `an answer is held to the start's bound on since`() {
        requireNotNull(decodeCallBellEvent(bell(at = now, since = now - CALL_BELL_TTL_SECONDS), roomId, roomKey, now))
        assertNull(decodeCallBellEvent(bell(at = now, since = now - CALL_BELL_TTL_SECONDS - 1), roomId, roomKey, now))
        // An end may still come long after its call began.
        requireNotNull(decodeCallBellEvent(bell(CallBellState.END, at = now, since = now - 3_600), roomId, roomKey, now))
    }

    /** A bell built by hand, so its body can carry what the encoder never would. */
    private fun rawBell(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): NostrEvent {
        val sig = Schnorr.sign(callBellMessage(roomId, CallBellState.ANSWER, call, now), deviceSk, Entropy.bytes(32)).toHex()
        val plaintext = buildJsonObject {
            put("v", 1)
            put("state", "answer")
            put("call", call.toJson())
            put("device", device)
            put("sig", sig)
            extra()
        }.toString()
        return Events.sign(
            secretKey = Entropy.bytes(32),
            kind = KIND_CALL_BELL,
            createdAt = now,
            tags = listOf(listOf("d", callBellTag(roomKey, now)), listOf("expiration", (now + CALL_BELL_TTL_SECONDS).toString())),
            content = Nip44.encrypt(plaintext, callBellContentKey(roomKey), Entropy.bytes(32)),
            auxRand = Entropy.bytes(32),
        )
    }
}
