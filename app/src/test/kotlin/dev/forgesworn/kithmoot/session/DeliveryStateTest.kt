package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.PublishEvidence
import dev.forgesworn.kithmoot.relay.RelayAckEvidence
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web client's `app/src/delivery-state.test.ts`, case for case, plus what only Android has: a quiet room's drop. */
class DeliveryStateTest {
    private fun relay(url: String, found: Boolean? = null, acked: Boolean = true, at: Long = 0, keeps: Boolean = false,
        lost: Boolean = false, readable: Boolean = true) = RelayAckEvidence(url, readable, found, acked, at, keeps, lost)
    private fun evidence(vararg relays: RelayAckEvidence, pending: Int = 0) = PublishEvidence(1460, relays.toList(), pending)
    private fun signed(id: String) = NostrEvent(kind = 1460, createdAt = 1, tags = emptyList(), content = "x",
        pubkey = "p", id = id.repeat(64).take(64), sig = "s")

    private class MemoryStorage : RoomStorage {
        var bytes: ByteArray? = null
        var broken = false
        override fun read(): ByteArray? { if (broken) error("no"); return bytes?.copyOf() }
        override fun write(value: ByteArray) { if (broken) error("no"); bytes = value.copyOf() }
        override fun reset() { if (broken) error("no"); bytes = null }
        fun text() = bytes?.toString(Charsets.UTF_8)
    }

    private inner class Rig(evidenceOf: ((String) -> PublishEvidence?)? = null) {
        val storage = MemoryStorage()
        var clock = 1_800_000_000_000L
        val known = mutableMapOf<String, PublishEvidence>()
        val accepted = mutableSetOf<String>()
        val asked = mutableListOf<String>()
        private val lookup = evidenceOf ?: { id -> known[id] }
        fun make() = DeliveryTicks(storage, lookup, { asked += it }, { it in accepted }, { clock })
        var ticks = make()
        fun advance(ms: Long) { clock += ms }
    }

    @Test fun `is sent when a relay returned this event, or has been seen to keep chat`() {
        assertEquals(TickState.SENT, sentState(evidence(relay("a", found = true))))
        assertEquals(TickState.SENT, sentState(evidence(relay("a", keeps = true))))
        // One relay that kept it is enough, whatever the others did.
        assertEquals(TickState.SENT, sentState(evidence(relay("a", found = false, lost = true), relay("b", found = true))))
        assertEquals(TickState.SENT, sentState(evidence(relay("a", lost = true), relay("b", keeps = true))))
    }

    @Test fun `is checking until a relay has answered`() {
        assertEquals(TickState.CHECKING, sentState(evidence(relay("a"))))
        assertEquals(TickState.CHECKING, sentState(evidence(relay("a"), relay("b", found = false))))
    }

    @Test fun `is not kept only when every acknowledging relay was seen to lose it`() {
        assertEquals(TickState.NOT_KEPT, sentState(evidence(relay("a", found = false, lost = true))))
        assertEquals(TickState.NOT_KEPT, sentState(evidence(relay("a", lost = true), relay("b", found = false))))
    }

    @Test fun `counts a relay that returned the event though its OK never arrived, and waits for one not yet asked`() {
        val lost = relay("a", found = false, lost = true)
        assertEquals(TickState.SENT, sentState(evidence(lost, relay("b", acked = false, found = true))))
        assertEquals(TickState.CHECKING, sentState(evidence(lost, relay("b", acked = false))))
        // What it did with other events says nothing about an event it never acknowledged.
        assertEquals(TickState.CHECKING, sentState(evidence(lost, relay("b", acked = false, keeps = true))))
        // Nowhere: every relay that was asked, acknowledged or not, returned nothing.
        assertEquals(TickState.NOT_KEPT, sentState(evidence(lost, relay("b", acked = false, found = false))))
    }

    @Test fun `is checking, not lost, while a relay is still working on the write`() {
        val lost = relay("a", found = false, lost = true)
        assertEquals(TickState.CHECKING, sentState(evidence(lost, pending = 1)))
        assertEquals(TickState.NOT_KEPT, sentState(evidence(lost, pending = 0)))
        // What has been kept is kept, whoever is still working.
        assertEquals(TickState.SENT, sentState(evidence(lost, relay("b", found = true), pending = 2)))
    }

    @Test fun `does not hold an earlier failure against a write that has not been checked, once it was sent again`() {
        val one = evidence(relay("a", lost = true))
        assertEquals(TickState.NOT_KEPT, sentState(one))
        assertEquals(TickState.CHECKING, sentState(one, trustRelayLoss = false))
        assertEquals(TickState.NOT_KEPT, sentState(evidence(relay("a", lost = true, found = false)), trustRelayLoss = false))
    }

    @Test fun `says nothing when a relay that took it cannot be asked, or when none is known`() {
        assertNull(sentState(null))
        assertNull(sentState(evidence()))
        assertNull(sentState(evidence(relay("a", readable = false))))
        // A write-only relay may hold it: never "not kept", but another relay's return still counts.
        assertNull(sentState(evidence(relay("a", found = false, lost = true), relay("w", readable = false))))
        assertEquals(TickState.SENT, sentState(evidence(relay("a", found = true), relay("w", readable = false))))
    }

    @Test fun `is those that render as a row, and not reactions, edits, retractions, invitations, logos or shared-work operations`() {
        val plain = ChatMessage("m", "p", "d", "hello", 1)
        assertTrue(isTickMessage(plain))
        assertFalse(isTickMessage(plain.copy(reaction = ChatReaction("x".repeat(64), "p", "👍", true, 1))))
        assertFalse(isTickMessage(plain.copy(replaces = "a")))
        assertFalse(isTickMessage(plain.copy(retracts = "a")))
        assertFalse(isTickMessage(plain.copy(assignment = signed("1"))))
    }

    @Test fun `shows a clock from the acknowledgement, then one tick when a relay returns it`() {
        val r = Rig(); val event = signed("1")
        r.ticks.note("m1", event)
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        assertTrue(r.ticks.waiting())
        r.ticks.refresh()
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        r.known[event.id] = evidence(relay("a", found = true))
        r.ticks.refresh()
        assertEquals(TickState.SENT, r.ticks.get("m1")?.state)
        assertNull(r.ticks.get("m1")?.event)
        assertFalse(r.ticks.waiting())
        assertEquals(TickState.SENT, r.ticks.records.value["m1"]?.state)
    }

    @Test fun `keeps the verdict across a restart, and the signed event of a message that was not kept`() {
        val r = Rig(); val kept = signed("1"); val lost = signed("2")
        r.ticks.note("kept", kept); r.ticks.note("lost", lost)
        r.known[kept.id] = evidence(relay("a", found = true))
        r.known[lost.id] = evidence(relay("a", found = false, lost = true))
        r.ticks.refresh()
        val reloaded = r.make()
        assertEquals(TickState.SENT, reloaded.get("kept")?.state); assertEquals(kept.id, reloaded.get("kept")?.eventId)
        assertEquals(TickState.NOT_KEPT, reloaded.get("lost")?.state); assertEquals(lost, reloaded.get("lost")?.event)
        assertNull(reloaded.get("other"))
    }

    @Test fun `does not keep a verdict it has not reached, so a restart shows no tick rather than a guess`() {
        val r = Rig()
        r.ticks.note("m1", signed("1"))
        assertNull(r.make().get("m1"))
        assertNull(r.storage.bytes)
    }

    @Test fun `retries the same signed event, and lets the new write answer for itself`() {
        val r = Rig(); val event = signed("1")
        r.ticks.note("m1", event)
        r.known[event.id] = evidence(relay("a", found = false, lost = true, at = r.clock))
        r.ticks.refresh()
        assertEquals(TickState.NOT_KEPT, r.ticks.get("m1")?.state)
        r.advance(5_000)
        assertEquals(event, r.ticks.retry("m1")?.event)
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        // What the relay did with the first write is not an answer about this one.
        r.ticks.refresh()
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        // It has acknowledged the new write but not been asked for it yet: its mark from before still is not an answer.
        r.known[event.id] = evidence(relay("a", lost = true, at = r.clock + 10))
        r.ticks.refresh()
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        r.known[event.id] = evidence(relay("a", found = true, keeps = true, at = r.clock + 10))
        r.ticks.refresh()
        assertEquals(TickState.SENT, r.ticks.get("m1")?.state)
    }

    @Test fun `is not kept again when the relays still return nothing for the retried write`() {
        val r = Rig(); val event = signed("1")
        r.ticks.note("m1", event)
        r.known[event.id] = evidence(relay("a", found = false, lost = true, at = r.clock))
        r.ticks.refresh(); r.advance(5_000); r.ticks.retry("m1")
        r.known[event.id] = evidence(relay("a", found = false, lost = true, at = r.clock + 10))
        r.ticks.refresh()
        assertEquals(TickState.NOT_KEPT, r.ticks.get("m1")?.state)
    }

    @Test fun `goes back to not kept when the retry itself fails, and offers Retry only for what is not kept`() {
        val r = Rig(); val event = signed("1")
        r.ticks.note("m1", event)
        assertNull(r.ticks.retry("m1"))
        r.known[event.id] = evidence(relay("a", found = false))
        r.ticks.refresh()
        r.ticks.retry("m1")
        r.ticks.failed("m1")
        assertEquals(TickState.NOT_KEPT, r.ticks.get("m1")?.state)
        assertEquals(event, r.make().get("m1")?.event)
    }

    @Test fun `asks the relays about a message by name once it has waited, and only once`() {
        val r = Rig(); val event = signed("1")
        r.known[event.id] = evidence(relay("a"))
        r.ticks.note("m1", event)
        r.advance(DeliveryTicks.ASK_BACK_AFTER_MS - 1); r.ticks.refresh()
        assertTrue(r.asked.isEmpty())
        r.advance(1); r.ticks.refresh(); r.ticks.refresh()
        assertEquals(listOf(event.id), r.asked)
    }

    @Test fun `drops the clock when nothing can ever say, rather than leaving it for ever`() {
        val r = Rig(); val event = signed("1")
        r.ticks.note("m1", event)
        r.advance(DeliveryTicks.NO_EVIDENCE_AFTER_MS - 1); r.ticks.refresh()
        assertNotNull(r.ticks.get("m1"))
        r.advance(1); r.ticks.refresh()
        assertNull(r.ticks.get("m1"))
        // A relay that cannot be asked is treated the same.
        r.known[signed("2").id] = evidence(relay("w", readable = false))
        r.ticks.note("m2", signed("2")); r.advance(DeliveryTicks.NO_EVIDENCE_AFTER_MS); r.ticks.refresh()
        assertNull(r.ticks.get("m2"))
    }

    @Test fun `gives up a clock that the relays never answer, rather than leaving it for ever`() {
        val r = Rig(); val event = signed("1")
        r.known[event.id] = evidence(relay("a"), relay("b", acked = false))
        r.ticks.note("m1", event)
        r.advance(DeliveryTicks.GIVE_UP_AFTER_MS - 1); r.ticks.refresh()
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        r.advance(1); r.ticks.refresh()
        assertNull(r.ticks.get("m1"))
    }

    @Test fun `shows one tick in a quiet room once its drop is accepted, which is all there is to say`() {
        val r = Rig(); val event = signed("1")
        r.ticks.note("m1", event, quiet = true)
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        assertNull(r.ticks.get("m1")?.event, "a quiet message is never offered again from here")
        // The inner event never reaches a relay by its own id: no evidence is no verdict, for a whole slot.
        r.advance(QuietTransport.SLOT_SECONDS * 1000); r.ticks.refresh()
        assertEquals(TickState.CHECKING, r.ticks.get("m1")?.state)
        r.accepted += event.id; r.ticks.refresh()
        assertEquals(TickState.SENT, r.ticks.get("m1")?.state)
        assertEquals(TickState.SENT, r.make().get("m1")?.state)
    }

    @Test fun `drops a quiet clock whose drop never went, as when a box holds the slot`() {
        val r = Rig()
        r.ticks.note("m1", signed("1"), quiet = true)
        r.advance(DeliveryTicks.QUIET_GIVE_UP_AFTER_MS - 1); r.ticks.refresh()
        assertNotNull(r.ticks.get("m1"))
        r.advance(1); r.ticks.refresh()
        assertNull(r.ticks.get("m1"))
    }

    @Test fun `forgets verdicts older than a week and keeps a bounded number`() {
        val r = Rig()
        val settle = { id: String, event: NostrEvent -> r.ticks.note(id, event); r.known[event.id] = evidence(relay("a", found = true)); r.ticks.refresh() }
        settle("old", signed("1"))
        r.advance(DeliveryTicks.DELIVERY_KEPT_MS + 1)
        settle("new", signed("2"))
        assertNull(r.make().get("old"))
        assertEquals(TickState.SENT, r.make().get("new")?.state)
        for (i in 0 until DeliveryTicks.MAX_DELIVERY_KEPT + 5) { r.advance(1000); settle("m$i", signed((i % 10).toString())) }
        assertEquals(DeliveryTicks.MAX_DELIVERY_KEPT, Json.parseToJsonElement(r.storage.text()!!).jsonArray.size)
    }

    @Test fun `keeps at most a bounded number of unsent copies for Retry`() {
        val r = Rig()
        for (i in 0 until DeliveryTicks.MAX_KEPT_EVENTS + 3) {
            val event = signed("%02x".format(i)); r.advance(1000)
            r.ticks.note("m$i", event); r.known[event.id] = evidence(relay("a", found = false)); r.ticks.refresh()
        }
        val reloaded = r.make()
        assertNull(reloaded.get("m0")?.event)
        assertEquals(TickState.NOT_KEPT, reloaded.get("m0")?.state)
        assertNotNull(reloaded.get("m${DeliveryTicks.MAX_KEPT_EVENTS + 2}")?.event)
    }

    @Test fun `forgets every verdict on request`() {
        val r = Rig(); val event = signed("1")
        r.ticks.note("m1", event); r.known[event.id] = evidence(relay("a", found = true)); r.ticks.refresh()
        assertNotNull(r.storage.bytes)
        r.ticks.forget()
        assertNull(r.ticks.get("m1"))
        assertNull(r.make().get("m1"))
    }

    @Test fun `a room that keeps nothing on the phone shows ticks for this visit only`() {
        val event = signed("1")
        val ticks = DeliveryTicks(null, { PublishEvidence(1460, listOf(relay("a", found = true))) })
        ticks.note("m1", event); ticks.refresh()
        assertEquals(TickState.SENT, ticks.get("m1")?.state)
    }

    @Test fun `reads damaged or unavailable storage as no evidence`() {
        val r = Rig()
        r.storage.bytes = "{not json".toByteArray()
        assertNull(r.make().get("m1"))
        r.storage.bytes = """[{"i":1},null,{"i":"a","e":"b","s":"delivered","a":1},{"i":"c","e":"d","s":"sent","a":"1"}]""".toByteArray()
        val damaged = r.make()
        assertNull(damaged.get("a")); assertNull(damaged.get("c"))
        r.storage.broken = true
        val broken = r.make()
        broken.note("m", signed("1")); broken.forget()
        assertNull(broken.get("m"))
    }

    @Test fun `keeps rooms apart, each in its own store`() {
        val one = Rig(); val two = Rig()
        val settle = { rig: Rig, event: NostrEvent -> rig.ticks.note("m1", event); rig.known[event.id] = evidence(relay("a", found = true)); rig.ticks.refresh() }
        settle(one, signed("1")); settle(two, signed("2"))
        assertNotEquals(one.make().get("m1")?.eventId, two.make().get("m1")?.eventId)
    }
}
