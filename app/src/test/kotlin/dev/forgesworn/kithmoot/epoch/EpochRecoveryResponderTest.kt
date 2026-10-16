package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.EpochGrant
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.decodeEpochGrant
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeEpochRequest
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.MemoryStorage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The creator's recovery endpoint must not be a presence probe for the people it refuses. */
class EpochRecoveryResponderTest {
    private val initial = ByteArray(32) { 7 }
    private val room = deriveRoom(initial).roomId
    private val roomKey = deriveRoom(initial).roomKey
    private val authoritySecret = Fixtures.key(71)
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private var clock = 103L

    private fun vault(removed: List<String>): EpochVault {
        val vault = EpochVault(MemoryStorage())
        vault.initialise(room, authority, initial, 100)
        vault.beginCatchUp(room, 0, RekeyNotice(1, removed, null, false, ByteArray(32) { 21 }, 101, catchUp = true),
            "aa".repeat(32), null, 101)
        vault.activate(room, 1, 102)
        return vault
    }

    private fun responder(vault: EpochVault, known: (String) -> Boolean = { false }, onUnknown: () -> Unit = {}) =
        EpochRecoveryResponder(vault, room, authoritySecret, roomKey, null, now = { clock }, known = known,
            onUnknown = { onUnknown() })

    private fun ask(who: PrimaryIdentity) =
        encodeEpochRequest(room, authority, roomKey, who.deviceSecretKey, who.credential, clock)

    private fun refusal(who: PrimaryIdentity, responder: EpochRecoveryResponder): String? {
        val request = ask(who)
        val answer = responder.answer(request) ?: return null
        return assertIs<EpochGrant.Refused>(decodeEpochGrant(answer, room, authority, who.deviceSecretKey, request.id, clock)).reason
    }

    @Test fun `a removed member is told once an hour however often it asks`() {
        val removed = Fixtures.primary(Fixtures.room(), 3, 4)
        val responder = responder(vault(listOf(removed.participant)))
        assertEquals("removed", refusal(removed, responder))
        // Fresh requests, each a new id, for the rest of the hour: silence.
        repeat(5) {
            clock += 600
            assertNull(refusal(removed, responder), "a removed device must not be able to ping the creator's phone")
        }
        clock = 103 + REFUSED_ANSWER_EVERY_SECONDS
        assertEquals("removed", refusal(removed, responder))
        clock += 1
        assertNull(refusal(removed, responder))
    }

    @Test fun `a closed room tells each participant once an hour, and is capped across participants per minute`() {
        val vault = vault(emptyList())
        vault.terminal(room, 1, RekeyNotice(2, emptyList(), null, true, null, 103), "bb".repeat(32), 103)
        val responder = responder(vault)
        val askers = (0 until MAX_REFUSALS_PER_MINUTE + 1).map { Fixtures.primary(Fixtures.room(), 100 + 2 * it, 101 + 2 * it) }
        for (asker in askers.take(MAX_REFUSALS_PER_MINUTE)) assertEquals("closed", refusal(asker, responder))
        // A crowd of fresh identities cannot draw more than the minute's budget.
        assertNull(refusal(askers.last(), responder))
        assertNull(refusal(askers.first(), responder), "and the first asker is still inside its hour")
        clock += 60
        assertEquals("closed", refusal(askers.last(), responder))
        assertNull(refusal(askers.first(), responder))
    }

    @Test fun `an unknown asker is answered once a minute, reported as before, and granted at once when let in`() {
        val removed = Fixtures.primary(Fixtures.room(), 3, 4)
        val stranger = Fixtures.primary(Fixtures.room(), 7, 8)
        val letIn = mutableSetOf<String>()
        var reports = 0
        val responder = responder(vault(listOf(removed.participant)), known = { it in letIn }, onUnknown = { reports += 1 })
        assertEquals("unknown", refusal(stranger, responder))
        clock += 10
        assertNull(refusal(stranger, responder), "a fresh request inside the minute is not answered")
        clock += 50
        assertEquals("unknown", refusal(stranger, responder))
        assertEquals(2, reports, "the let-them-in prompt still comes back each minute")
        letIn += stranger.participant.lowercase()
        clock += 1
        val request = ask(stranger)
        val grant = assertNotNull(responder.answer(request), "a grant is never rate limited")
        assertIs<EpochGrant.Current>(decodeEpochGrant(grant, room, authority, stranger.deviceSecretKey, request.id, clock))
    }

    @Test fun `the quiet reply delay is uniform between half a second and two seconds`() {
        assertEquals(QUIET_REPLY_MIN_MS, quietReplyDelayMs { 0.0 })
        assertEquals(QUIET_REPLY_MAX_MS, quietReplyDelayMs { 1.0 })
        assertEquals(1_250L, quietReplyDelayMs { 0.5 })
        repeat(1_000) { assertTrue(quietReplyDelayMs() in 500L..2_000L) }
    }
}
