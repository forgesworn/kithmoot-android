package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.SpryKey
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.session.SpryRosterRow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The registrar and voucher rules of the web's `app/src/spry-room.ts`, against spry-turn `docs/api.md` "Room-grant paths". */
class SpryRoomRegistrarTest {
    private fun key(n: Int) = SpryKey.publicKeyHex(ByteArray(32) { (n + 1).toByte() })
    private val own = key(0)
    private val me = "aa".repeat(32)
    private fun person(n: Int) = "%064x".format(n + 100)
    private fun row(n: Int, participant: String = person(n), agent: Boolean = false, spry: String? = key(n)) =
        SpryRosterRow("device-$n", participant, spry, agent)
    private val self = SpryRosterRow("device-self", me, own, false)

    /** A node that answers like spry-turn: counts keys, honours a seal, never unseals. */
    private class Node(var sealedAnswer: Boolean? = null, val failWith: MutableList<Int> = mutableListOf()) {
        val requests = mutableListOf<Pair<SpryDevicesChange, Long>>()
        val keys = linkedSetOf<String>()
        var sealed = false
        suspend fun send(change: SpryDevicesChange, revision: Long): SpryDevicesAnswer {
            requests += change to revision
            failWith.removeFirstOrNull()?.let { throw SpryRequestException(it) }
            keys -= change.remove.toSet(); keys += change.add
            if (change.seal) sealed = true
            return SpryDevicesAnswer(sealedAnswer ?: sealed, enforced = sealed, devices = keys.size)
        }
    }

    private fun registrar(clock: () -> Long = { 1_000_000 }) = SpryRegistrar(own, me, clock)

    @Test fun `a device that does not share registers only its own key and seals once every present device announces one`() = runTest {
        val node = Node()
        val r = registrar()
        r.register("token", listOf(self, row(1), row(2)), emptySet(), share = false, send = node::send)
        assertEquals(listOf(SpryDevicesChange(add = listOf(own)), SpryDevicesChange(seal = true)), node.requests.map { it.first })
        assertTrue(r.sealed)
    }

    @Test fun `where it shares, members' keys go after its own, then a seal in a request of its own`() = runTest {
        val node = Node()
        registrar().register("token", listOf(self, row(1), row(2)), emptySet(), share = true, send = node::send)
        assertEquals(listOf(SpryDevicesChange(add = listOf(own, key(1), key(2))), SpryDevicesChange(seal = true)), node.requests.map { it.first })
        for ((change, _) in node.requests) {
            assertTrue(change.add.none { it in change.remove })
            assertFalse(change.seal && (change.add.isNotEmpty() || change.remove.isNotEmpty()))
        }
    }

    @Test fun `an older client present keeps the grant open and agents neither count nor are registered`() = runTest {
        val node = Node()
        val r = registrar()
        val agent = row(3, agent = true)
        r.register("token", listOf(self, row(1), agent, row(2, spry = null)), emptySet(), share = true, send = node::send)
        assertEquals(listOf(SpryDevicesChange(add = listOf(own, key(1)))), node.requests.map { it.first })
        assertFalse(r.sealed)
        // The older client leaves; an agent without a key does not hold the seal back.
        r.register("token", listOf(self, row(1), row(4, agent = true, spry = null)), emptySet(), share = true, send = node::send)
        assertEquals(SpryDevicesChange(seal = true), node.requests.last().first)
    }

    @Test fun `at most two keys per participant, counting what is already registered`() = runTest {
        val node = Node()
        val r = registrar()
        val alice = person(1)
        r.register("token", listOf(self, row(1, alice), row(2, alice)), emptySet(), share = true, send = node::send)
        r.register("token", listOf(self, row(1, alice), row(2, alice), row(3, alice)), emptySet(), share = true, send = node::send)
        assertEquals(setOf(own, key(1), key(2)), node.keys)
        assertFalse(r.registered(key(3)))
    }

    @Test fun `other members stop at 56 keys while this device's own still goes`() = runTest {
        val node = Node()
        val r = registrar()
        // A grant already holding 56 entries (a full room, or many vouches).
        repeat(56) { node.keys += "%064x".format(it) }
        r.register("token", listOf(row(1)), emptySet(), share = true, send = node::send)
        // The first batch leads with this device's own key and carries whoever fits in it.
        assertEquals(listOf(own, key(1)), node.requests.first().first.add)
        r.register("token", listOf(row(1), row(2), row(3)), emptySet(), share = true, send = node::send)
        // Now the node has said 58: nobody else is added.
        assertEquals(1, node.requests.count { it.first.add.isNotEmpty() })
        assertFalse(r.registered(key(2)))
    }

    @Test fun `adds go 32 at a time, later batches within the headroom`() = runTest {
        val node = Node()
        val roster = listOf(self) + (1..50).map { row(it) }
        registrar().register("token", roster, emptySet(), share = true, send = node::send)
        val adds = node.requests.map { it.first.add.size }.filter { it > 0 }
        assertEquals(listOf(32, 19), adds)
    }

    @Test fun `a removed participant's keys go in a removal of their own, and a device leaving removes nothing`() = runTest {
        val node = Node()
        val r = registrar()
        val bob = person(2)
        r.register("token", listOf(self, row(1), row(2, bob)), emptySet(), share = true, send = node::send)
        node.requests.clear()
        // Bob's device leaves: nothing is removed.
        r.register("token", listOf(self, row(1)), emptySet(), share = true, send = node::send)
        assertTrue(node.requests.none { it.first.remove.isNotEmpty() })
        // Bob is removed from the room.
        r.register("token", listOf(self, row(1)), setOf(bob.uppercase()), share = true, send = node::send)
        val removal = node.requests.single { it.first.remove.isNotEmpty() }.first
        assertEquals(SpryDevicesChange(remove = listOf(key(2))), removal)
        assertFalse(r.registered(key(2)))
    }

    @Test fun `every answer re-decides the seal, and a new grant starts again`() = runTest {
        val node = Node(sealedAnswer = false)
        val r = registrar()
        r.register("token", listOf(self), emptySet(), share = true, send = node::send)
        assertEquals(SpryDevicesChange(seal = true), node.requests.last().first)
        // The node said sealed:false (say it restarted): asked again on the next pass.
        r.register("token", listOf(self), emptySet(), share = true, send = node::send)
        assertEquals(2, node.requests.count { it.first.seal })
        node.sealedAnswer = null
        r.register("token", listOf(self), emptySet(), share = true, send = node::send)
        val sealedAt = node.requests.size
        r.register("token", listOf(self), emptySet(), share = true, send = node::send)
        assertEquals(sealedAt, node.requests.size)
        // A fresh grant (sharing turned off and on), unsealed on the node: own key and seal again.
        val fresh = Node()
        r.register("other", listOf(self), emptySet(), share = true, send = fresh::send)
        assertEquals(listOf(SpryDevicesChange(add = listOf(own)), SpryDevicesChange(seal = true)), fresh.requests.map { it.first })
    }

    @Test fun `out of date is retried once with a larger revision, and an unknown path is left alone for an hour`() = runTest {
        var now = 1_000_000L
        val node = Node(failWith = mutableListOf(409))
        val r = registrar { now }
        r.register("token", listOf(self), emptySet(), share = false, send = node::send)
        assertEquals(SpryDevicesChange(add = listOf(own)), node.requests[0].first)
        assertEquals(node.requests[0].first, node.requests[1].first)
        assertTrue(node.requests[1].second > node.requests[0].second)
        assertTrue(node.requests.zipWithNext().all { (a, b) -> b.second > a.second })

        val missing = Node(failWith = mutableListOf(404))
        val other = registrar { now }
        assertFailsWith<SpryRequestException> { other.register("token", listOf(self), emptySet(), share = false, send = missing::send) }
        other.register("token", listOf(self), emptySet(), share = false, send = missing::send)
        assertEquals(1, missing.requests.size)
        now += SPRY_UNSUPPORTED_MS
        other.register("token", listOf(self), emptySet(), share = false, send = missing::send)
        assertEquals(3, missing.requests.size)
    }

    @Test fun `a blocked person's devices are registered like anybody admitted`() = runTest {
        // A block stays on this phone and never reaches the roster, so the
        // registrar has nothing to leave them out by, and must not.
        val node = Node()
        val blocked = row(7)
        registrar().register("token", listOf(self, blocked), emptySet(), share = true, send = node::send)
        assertTrue(key(7) in node.keys)
    }

    // --- vouching -----------------------------------------------------------

    private val offer = SpryOffer("https://relay.example.org", "a".repeat(43), 2_000_000_000)
    private val mine = SpryOffer("https://mine.example.org", "b".repeat(43), 2_000_000_000)

    @Test fun `members vouch only for newcomers, once each, never agents or themselves, at most two per person`() {
        var now = 5_000_000L
        val v = SpryVoucher("device-self", own, { now }) { 1_000 }
        val present = listOf(self, row(1))
        // Not redeemed yet: nothing to vouch with.
        assertEquals(emptyList(), v.due(listOf(offer), null, present))
        v.redeemed(offer)
        // The first look only notes who is already here.
        assertEquals(emptyList(), v.due(listOf(offer), null, present))
        val carol = person(5)
        val later = present + listOf(row(2), row(3, agent = true), row(4, spry = null), row(5, carol), row(6, carol), SpryRosterRow("device-8", carol, key(8), false))
        val due = v.due(listOf(offer), null, later)
        assertEquals(listOf(key(2), key(5), key(6)), due.map { it.key })
        assertTrue(due.all { it.offer == offer })
        assertEquals(emptyList(), v.due(listOf(offer), null, later))
    }

    @Test fun `no vouching with this device's own grant or a redemption ten minutes old`() {
        var now = 5_000_000L
        val v = SpryVoucher("device-self", own, { now }) { 1_000 }
        v.redeemed(mine)
        assertEquals(emptyList(), v.due(listOf(mine), mine.token, listOf(self)))
        assertEquals(emptyList(), v.due(listOf(mine), mine.token, listOf(self, row(1))))
        v.redeemed(offer)
        assertEquals(emptyList(), v.due(listOf(offer), mine.token, listOf(self)))
        now += SPRY_VOUCH_FRESH_MS
        assertEquals(emptyList(), v.due(listOf(offer), mine.token, listOf(self, row(1))))
        now -= 1
        assertEquals(listOf(key(1)), v.due(listOf(offer), mine.token, listOf(self, row(1))).map { it.key })
    }

    @Test fun `the vouch delay is one to five seconds`() {
        val v = SpryVoucher("device-self", own, { 1L })
        v.redeemed(offer)
        v.due(listOf(offer), null, listOf(self))
        val delays = v.due(listOf(offer), null, (1..200).map { row(it) }).map { it.delayMs }
        assertTrue(delays.all { it in 1_000..4_999 })
        assertTrue(delays.distinct().size > 1)
    }
}
