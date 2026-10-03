package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochRequest
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.session.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The member desk's decisions, driven directly: fold-kit's desk rules, one at a time. */
class MemberEpochResponderTest {
    private val now = 1_000L
    private val roomSecret = ByteArray(32) { 7 }
    private val room = Fixtures.room()
    private val authoritySecret = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private val member = Fixtures.primary(room, 5, 6)
    private val asker = Fixtures.primary(room, 1, 2)
    private val epochs = (1..3).map { RoomEpoch(it, ByteArray(32) { n -> (60 + it + n).toByte() }) }

    private fun rekeys(commitTop: Boolean = true, signer: ByteArray = authoritySecret): List<NostrEvent> {
        var previous = deriveEpoch(RoomEpoch(0, roomSecret))
        return epochs.map { next ->
            encodeRekeyEvent(room.roomId, signer, previous, next, listOf(member.devicePubkey), emptyList(), now,
                commit = commitTop || next.epoch < epochs.last().epoch).also { previous = deriveEpoch(next) }
        }
    }

    private class World(var current: RoomEpoch?, var rekeys: List<NostrEvent>, var secrets: Map<Int, ByteArray>) {
        var removed: List<String> = emptyList()
        var closed = false
        /** Who the room knows (kithmoot#207); the asker is a member unless a test says not. */
        val known = mutableSetOf<String>()
        val reported = mutableListOf<String>()
        var clock = 1_000L
    }

    private fun desk(world: World, jitter: Long = 1_500, random: Double = 0.5, maxBytes: Int = 60_000) = MemberEpochResponder(
        room.roomId, authority, member.deviceSecretKey, room.roomKey, null,
        current = { world.current },
        secretAt = { world.secrets[it] },
        rekeyAt = { n -> world.rekeys.getOrNull(n - 1) },
        removed = { world.removed },
        known = { it in world.known },
        closed = { world.closed },
        now = { world.clock },
        jitterMs = jitter,
        random = { random },
        maxGrantBytes = maxBytes,
        onUnknown = { world.reported += it.participant },
    )

    private fun world() = World(epochs.last(), rekeys(), epochs.associate { it.epoch to it.secret }).also { it.known += asker.participant.lowercase() }
    private fun ask(have: Int = 0, from: dev.forgesworn.kithmoot.session.PrimaryIdentity = asker, at: Long = now) =
        encodeMemberEpochRequest(room.roomId, authority, room.roomKey, from.deviceSecretKey, from.credential, have, at)

    @Test fun `a chain is built from the requester's epoch to this device's, and the requester accepts it`() {
        val w = world()
        val chain = assertNotNull(memberGrantChain(room.roomId, authority, room.roomKey, 0, epochs.last(), { w.secrets[it] }, { w.rekeys.getOrNull(it - 1) }))
        assertEquals(listOf(1, 2, 3), chain.epochs.map { it.epoch })
        val desk = desk(w)
        val request = ask()
        val decision = assertIs<MemberDeskDecision.Answer>(desk.onRequest(request, 3))
        assertEquals(750, decision.delayMs)
        val grant = assertNotNull(desk.answer(decision.request, 3))
        val accepted = decodeMemberEpochGrant(grant, room.roomId, authority, asker.deviceSecretKey, setOf(request.id), 0, room.roomKey, asker.participant, now)
        assertEquals(3, accepted?.epoch?.epoch)
        // From the middle, only what is missing.
        val partial = desk.onRequest(ask(have = 2), 3)
        assertIs<MemberDeskDecision.Answer>(partial)
        assertEquals(listOf(3), memberGrantChain(room.roomId, authority, room.roomKey, 2, epochs.last(), { w.secrets[it] }, { w.rekeys.getOrNull(it - 1) })?.epochs?.map { it.epoch })
    }

    @Test fun `no whole chain, no answer`() {
        val w = world()
        fun chain(secretAt: (Int) -> ByteArray? = { w.secrets[it] }, rekeyAt: (Int) -> NostrEvent? = { w.rekeys.getOrNull(it - 1) }, have: Int = 0, current: RoomEpoch = epochs.last()) =
            memberGrantChain(room.roomId, authority, room.roomKey, have, current, secretAt, rekeyAt)
        assertNull(chain(secretAt = { if (it == 2) null else w.secrets[it] }), "a secret missing")
        assertNull(chain(rekeyAt = { if (it == 1) null else w.rekeys.getOrNull(it - 1) }), "a rekey missing")
        assertNull(chain(rekeyAt = { n -> rekeys(signer = Fixtures.key(42)).getOrNull(n - 1) }), "not the authority's")
        assertNull(chain(rekeyAt = { n -> w.rekeys.getOrNull(n % 3) }), "out of order")
        assertNull(chain(have = 3), "nothing to hand on")
        assertNull(chain(have = 0, current = RoomEpoch(40, ByteArray(32))), "longer than 32 epochs")
        // A readable legacy top is the authority's to hand on; an unreadable one is offered.
        val legacy = rekeys(commitTop = false)
        assertNull(chain(rekeyAt = { legacy.getOrNull(it - 1) }))
        assertNotNull(chain(secretAt = { null }, rekeyAt = { legacy.getOrNull(it - 1) }, have = 2))
    }

    @Test fun `refusals are silent, and strangers, own requests and repeats are ignored`() {
        val w = world()
        val desk = desk(w)
        assertEquals(MemberDeskDecision.Ignore, desk.onRequest(ask(from = member), 3), "its own")
        val stranger = encodeMemberEpochRequest(room.roomId, authority, ByteArray(32) { 9 }, asker.deviceSecretKey, asker.credential, 0, now)
        assertEquals(MemberDeskDecision.Ignore, desk.onRequest(stranger, 3), "no room key")
        assertEquals(MemberDeskDecision.Ignore, desk.onRequest(ask(at = now - 91), 3), "stale")
        val request = ask()
        assertIs<MemberDeskDecision.Answer>(desk.onRequest(request, 3))
        assertEquals(MemberDeskDecision.Ignore, desk.onRequest(request, 3), "answered once")

        w.removed = listOf(asker.participant)
        assertEquals("removed", assertIs<MemberDeskDecision.Refused>(desk(w).onRequest(ask(), 3)).why)
        w.removed = emptyList()
        w.closed = true
        assertEquals("closed", assertIs<MemberDeskDecision.Refused>(desk(w).onRequest(ask(), 3)).why)
    }

    @Test fun `after a removal a participant the room does not know is not answered, and is reported once a minute`() {
        val w = world()
        w.removed = listOf(Fixtures.primary(room, 9, 10).participant)
        val stranger = Fixtures.primary(room, 11, 12)
        val desk = desk(w)
        assertEquals("unknown", assertIs<MemberDeskDecision.Refused>(desk.onRequest(ask(from = stranger), 3)).why)
        // A fresh request every few seconds while they wait: reported once.
        assertEquals("unknown", assertIs<MemberDeskDecision.Refused>(desk.onRequest(ask(from = stranger, at = now + 1), 3)).why)
        assertEquals(listOf(stranger.participant), w.reported)
        w.clock += 60
        desk.onRequest(ask(from = stranger, at = now + 60), 3)
        assertEquals(2, w.reported.size)
        // The member the room knows is answered as ever.
        assertIs<MemberDeskDecision.Answer>(desk.onRequest(ask(at = now + 60), 3))
        // Let in: their next ask is answered.
        w.known += stranger.participant.lowercase()
        val decision = assertIs<MemberDeskDecision.Answer>(desk.onRequest(ask(from = stranger, at = now + 61), 3))
        assertNotNull(desk.answer(decision.request, 3))
    }

    @Test fun `a room that never removed anybody answers a newcomer as it always did`() {
        val w = world()
        w.known.clear()
        assertIs<MemberDeskDecision.Answer>(desk(w).onRequest(ask(), 3))
    }

    @Test fun `only a device in step and ahead answers`() {
        val w = world()
        assertEquals(MemberDeskDecision.Ignore, desk(w).onRequest(ask(), null), "session not in step")
        assertEquals(MemberDeskDecision.Ignore, desk(w).onRequest(ask(), 2), "session and vault disagree")
        assertEquals(MemberDeskDecision.Ignore, desk(w).onRequest(ask(have = 3), 3), "not behind")
        w.current = null
        assertEquals(MemberDeskDecision.Ignore, desk(w).onRequest(ask(), 3), "vault not active")
        // Checked again once the wait is over.
        val moving = world()
        val desk = desk(moving)
        val decision = assertIs<MemberDeskDecision.Answer>(desk.onRequest(ask(), 3))
        moving.removed = listOf(asker.participant)
        assertNull(desk.answer(decision.request, 3))
    }

    @Test fun `it stands down once for another grant to the same device, then answers`() {
        val w = world()
        val desk = desk(w)
        val first = assertIs<MemberDeskDecision.Answer>(desk.onRequest(ask(), 3))
        // A junk 20472 to the asker, from anybody.
        desk.onGrant(encodeMemberEpochGrant(room.roomId, asker.devicePubkey, "1f".repeat(32), epochs.take(1), rekeys().take(1), now))
        assertNull(desk.answer(first.request, 3))
        val second = assertIs<MemberDeskDecision.Answer>(desk.onRequest(ask(at = now + 1), 3))
        desk.onGrant(encodeMemberEpochGrant(room.roomId, asker.devicePubkey, "1f".repeat(32), epochs.take(1), rekeys().take(1), now))
        assertNotNull(desk.answer(second.request, 3), "never stands down twice running")
    }

    @Test fun `its own grant echoed back is not somebody else's`() {
        val w = world()
        val desk = desk(w, jitter = 0)
        val first = assertIs<MemberDeskDecision.Answer>(desk.onRequest(ask(), 3))
        assertEquals(0, first.delayMs)
        val mine = assertNotNull(desk.answer(first.request, 3))
        val again = assertIs<MemberDeskDecision.Answer>(desk.onRequest(ask(at = now + 1), 3))
        // The relay echoes this desk's own grant while the next answer waits.
        desk.onGrant(mine)
        assertNotNull(desk.answer(again.request, 3))
    }

    @Test fun `the jitter stays below its bound, and a grant over the byte budget is not sent`() {
        val w = world()
        assertTrue(assertIs<MemberDeskDecision.Answer>(desk(w, random = 0.9999999).onRequest(ask(), 3)).delayMs < 1_500)
        val tight = desk(w, maxBytes = 1_000)
        val decision = assertIs<MemberDeskDecision.Answer>(tight.onRequest(ask(), 3))
        assertNull(tight.answer(decision.request, 3))
    }
}
