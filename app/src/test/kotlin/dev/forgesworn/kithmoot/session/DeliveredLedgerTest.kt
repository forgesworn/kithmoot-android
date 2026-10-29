package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.DeliveryReceipt
import dev.forgesworn.kithmoot.protocol.deliveryReceiptIdHash
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web client's `delivery-receipts.test.ts`: who confirmed this person's messages, and what the ticks say. */
class DeliveredLedgerTest {
    private class MemoryStorage : RoomStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(value: ByteArray) { bytes = value.copyOf() }
        override fun reset() { bytes = null }
    }

    private val me = "aa".repeat(32)
    private val ann = "bb".repeat(32)
    private val bo = "cc".repeat(32)
    private val bot = "dd".repeat(32)
    private var clock = 1_800_000_000_000L
    private val nowS get() = clock / 1000

    private fun mine(id: String, sentAt: Long = nowS - 100) = ChatMessage(id, me, "ee".repeat(32), "hi", sentAt)
    private fun receipt(from: String, at: Long, vararg ids: String, agent: Boolean = false) =
        DeliveryReceipt("ff".repeat(32), at, from, "11".repeat(32), agent, ids.map(::deliveryReceiptIdHash))
    private fun ledger(storage: RoomStorage? = MemoryStorage()) = DeliveredLedger(storage, now = { clock })

    @Test fun `a receipt for this person's message records who and when, earliest first`() {
        val ledger = ledger()
        ledger.own(mine("m1"))
        ledger.receipt(receipt(bo, nowS - 10, "m1"), me)
        ledger.receipt(receipt(ann, nowS - 20, "m1"), me)
        assertEquals(listOf(ann, bo), ledger.delivered("m1").map { it.participant })
        assertEquals(ledger.state.value["m1"], ledger.delivered("m1"))
    }

    @Test fun `a receipt that arrives before the message it names is matched when the message loads`() {
        val ledger = ledger()
        ledger.receipt(receipt(ann, nowS - 5, "m1", "somebody-elses"), me)
        assertTrue(ledger.delivered("m1").isEmpty())
        ledger.own(mine("m1"))
        assertEquals(listOf(ann), ledger.delivered("m1").map { it.participant })
        assertTrue(ledger.state.value.keys == setOf("m1"))
    }

    @Test fun `this person's own devices never count, and one person counts once with their first time`() {
        val ledger = ledger()
        ledger.own(mine("m1"))
        ledger.receipt(receipt(me, nowS - 30, "m1"), me)
        ledger.receipt(receipt(me.uppercase(), nowS - 30, "m1"), me)
        assertTrue(ledger.delivered("m1").isEmpty())
        ledger.receipt(receipt(ann, nowS - 10, "m1"), me)
        ledger.receipt(receipt(ann, nowS - 40, "m1", agent = true), me)
        val only = ledger.delivered("m1").single()
        assertEquals(nowS - 40, only.at)
        assertTrue(only.agent)
    }

    @Test fun `what was confirmed survives a restart, and forgetting the room removes it`() {
        val storage = MemoryStorage()
        ledger(storage).apply { own(mine("m1")); receipt(receipt(ann, nowS - 10, "m1", agent = true), me) }
        val again = ledger(storage)
        assertEquals(listOf(DeliveredBy(ann, nowS - 10, true)), again.delivered("m1"))
        again.forget()
        assertNull(storage.bytes)
        assertTrue(ledger(storage).delivered("m1").isEmpty())
    }

    @Test fun `confirmations older than a week are dropped`() {
        val storage = MemoryStorage()
        ledger(storage).apply { own(mine("m1")); receipt(receipt(ann, nowS - 10, "m1"), me) }
        clock += 8L * 24 * 3600 * 1000
        val later = ledger(storage)
        later.own(mine("m2", nowS - 5))
        assertTrue(later.delivered("m1").isEmpty())
    }

    @Test fun `relays are asked from the newest receipt read, else this person's oldest recent message, else now`() {
        val storage = MemoryStorage()
        val ledger = ledger(storage)
        assertEquals(nowS, ledger.since())
        ledger.own(mine("m1", nowS - 1_000))
        assertEquals(nowS - 1_060, ledger.since())
        ledger.receipt(receipt(ann, nowS - 50, "m1"), me)
        assertEquals(nowS - 650, ledger.since())
        assertEquals(nowS - 650, ledger(storage).since())
        // Never more than a week back.
        val old = ledger(MemoryStorage())
        old.receipt(receipt(ann, nowS - 700_000, "x"), me)
        assertEquals(nowS - 604_800, old.since())
    }

    @Test fun `an agent lights two ticks only when nobody but agents is known`() {
        val agentOnly = listOf(DeliveredBy(bot, 1, true))
        assertNotNull(deliveredView(agentOnly, me, null, knownPeople = false))
        assertNull(deliveredView(agentOnly, me, null, knownPeople = true))
        val both = listOf(DeliveredBy(bot, 1, true), DeliveredBy(ann, 2, false))
        val view = deliveredView(both, me, null, knownPeople = true)!!
        assertEquals(listOf(ann), view.people.map { it.participant })
        assertEquals(listOf(bot), view.agents.map { it.participant })
        assertNull(view.of)
        assertNull(deliveredView(listOf(DeliveredBy(me, 1, false)), me, null, knownPeople = false))
    }

    @Test fun `a fixed member list counts against its size and names who has not confirmed`() {
        val view = deliveredView(listOf(DeliveredBy(ann, 1, false)), me, listOf(me, ann, bo, bo), knownPeople = true)!!
        assertEquals(2, view.of)
        assertEquals(listOf(bo), view.waiting)
    }

    @Test fun `the audience is the roster, the log's authors and whoever confirmed, agents apart`() {
        assertEquals(DeliveryAudience(false, 1), deliveryAudience(me, listOf(me to false, bot to true), listOf(me, bot), emptyList()))
        assertEquals(DeliveryAudience(true, 2), deliveryAudience(me, listOf(bot to true), listOf(ann), emptyList()))
        assertEquals(DeliveryAudience(true, 3), deliveryAudience(me, emptyList(), emptyList(),
            listOf(DeliveredBy(ann, 1, false), DeliveredBy(bo, 1, false), DeliveredBy(bot, 1, true))))
    }

    @Test fun `ticks follow the log, and a person who only wrote holds back an agent's confirmation`() {
        val confirmed = mapOf("m1" to listOf(DeliveredBy(bot, 1, true)))
        val log = listOf(mine("m1"))
        assertNotNull(deliveredTicks(confirmed, me, listOf(bot to true), log, null)["m1"])
        val withAnn = log + ChatMessage("a1", ann, "22".repeat(32), "hello", nowS)
        assertNull(deliveredTicks(confirmed, me, listOf(bot to true), withAnn, null)["m1"])
        val tick = deliveredTicks(mapOf("m1" to listOf(DeliveredBy(ann, 1, false))), me, emptyList(), withAnn, null).getValue("m1")
        assertEquals(1, tick.knownOthers)
    }
}
