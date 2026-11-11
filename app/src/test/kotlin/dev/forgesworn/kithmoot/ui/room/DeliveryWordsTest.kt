package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.DeliveredBy
import dev.forgesworn.kithmoot.session.DeliveredTick
import dev.forgesworn.kithmoot.session.DeliveredView
import dev.forgesworn.kithmoot.session.TickState
import dev.forgesworn.kithmoot.ui.DeliveryWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words match the web client's `deliveryWords` in `app/src/delivery-state.ts` and `deliveredWords` in `app/src/delivery-receipts.ts`. */
class DeliveryWordsTest {
    @Test fun `each state has the same label and title as the web client`() {
        assertEquals(DeliveryWords.Words("Sending", "Sending…"), DeliveryWords.of(TickState.CHECKING))
        assertEquals(DeliveryWords.Words("Sent", "Sent. Not yet confirmed on anyone else’s device."), DeliveryWords.of(TickState.SENT))
        assertEquals(DeliveryWords.Words("Not kept. Retry", "Not kept: a relay accepted it but did not keep it."), DeliveryWords.of(TickState.NOT_KEPT))
        assertEquals("Retry", DeliveryWords.RETRY)
    }

    @Test fun `no state claims anyone has received or read the message`() {
        TickState.entries.map { DeliveryWords.of(it) }.forEach { words ->
            listOf(words.label, words.title).forEach { text ->
                assertFalse(text.contains("delivered", ignoreCase = true), text)
                assertFalse(text.contains("read", ignoreCase = true), text)
                assertFalse(text.contains("received", ignoreCase = true), text)
            }
        }
    }

    private val ann = DeliveredBy("bb".repeat(32), 1, false)
    private val bo = DeliveredBy("cc".repeat(32), 2, false)
    private val bot = DeliveredBy("dd".repeat(32), 3, true)
    private val names = mapOf(ann.participant to "Ann", bo.participant to "Bo", bot.participant to "Clerk")
    private fun nameOf(p: String) = names.getValue(p)

    @Test fun `two ticks name the one person in a room of two, and count otherwise`() {
        // A direct message or a two-person list: "Delivered", labelled with who.
        assertEquals(DeliveryWords.Words("Delivered to Ann", "Delivered"),
            DeliveryWords.delivered(DeliveredView(listOf(ann), emptyList(), waiting = emptyList(), of = 1), ::nameOf, 1))
        // A fixed list: "of N".
        assertEquals(DeliveryWords.Words("Delivered to 1 of 3", "Delivered to 1 of 3"),
            DeliveryWords.delivered(DeliveredView(listOf(ann), emptyList(), waiting = listOf(bo.participant, "ee".repeat(32)), of = 3), ::nameOf, 3))
        // A link room with only one other known.
        assertEquals(DeliveryWords.Words("Delivered to Ann", "Delivered"), DeliveryWords.delivered(DeliveredView(listOf(ann), emptyList()), ::nameOf, 1))
        // A link room: a count, never "of N".
        assertEquals(DeliveryWords.Words("Delivered to 1", "Delivered to 1"), DeliveryWords.delivered(DeliveredView(listOf(ann), emptyList()), ::nameOf, 4))
        assertEquals(DeliveryWords.Words("Delivered to 2", "Delivered to 2"), DeliveryWords.delivered(DeliveredView(listOf(ann, bo), listOf(bot)), ::nameOf, 3))
        // A person and their own agent: the agent counts.
        assertEquals(DeliveryWords.Words("Delivered to Clerk", "Delivered"), DeliveryWords.delivered(DeliveredView(emptyList(), listOf(bot)), ::nameOf, 1))
    }

    @Test fun `the sheet heads with the count against a list, else with the label, and never says read`() {
        val listed = DeliveredView(listOf(ann), emptyList(), waiting = listOf(bo.participant), of = 2)
        assertEquals("Delivered to 1 of 2", DeliveryWords.deliveredHeading(listed, DeliveryWords.delivered(listed, ::nameOf, 2)))
        val pair = DeliveredView(listOf(ann), emptyList())
        assertEquals("Delivered to Ann", DeliveryWords.deliveredHeading(pair, DeliveryWords.delivered(pair, ::nameOf, 1)))
        assertEquals("Delivered means their device received it. It does not mean they read it.", DeliveryWords.DELIVERED_FOOTNOTE)
        assertEquals("People not listed may be away, or using an app that does not confirm delivery.", DeliveryWords.DELIVERED_LINK_ROOM_NOTE)
        assertTrue(DeliveryWords.SETTING_SUMMARY.contains("never says whether you read it"))
    }

    // The line under the newest own message. The clock stands in for the message time's formatter.
    private fun clock(seconds: Long) = "t$seconds"
    private fun line(tick: TickState?, confirmed: DeliveredTick? = null, quiet: Boolean = false, soleOther: String? = null) =
        DeliveryWords.statusLine(tick, confirmed, ::nameOf, quiet, soleOther, ::clock)
    private val middleDot = "\u00B7"

    @Test fun `two ticks say who has it and when the latest confirmation came`() {
        // One person in a room of two, named.
        assertEquals("Delivered to Ann $middleDot t1",
            line(TickState.SENT, DeliveredTick(DeliveredView(listOf(ann), emptyList(), waiting = emptyList(), of = 1), 1)))
        // A count in a link room, with the latest of the confirmations, not the first or the last listed.
        val early = DeliveredBy(ann.participant, 40, false)
        val late = DeliveredBy(bo.participant, 90, false)
        val middle = DeliveredBy("ee".repeat(32), 60, false)
        assertEquals("Delivered to 3 $middleDot t90", line(null, DeliveredTick(DeliveredView(listOf(early, late, middle), emptyList()), 5)))
        assertEquals("Delivered to 3 $middleDot t90", line(null, DeliveredTick(DeliveredView(listOf(early, middle, late), emptyList()), 5)))
        // Against a fixed list.
        assertEquals("Delivered to 2 of 4 $middleDot t90", line(TickState.CHECKING, DeliveredTick(
            DeliveredView(listOf(early, late), emptyList(), waiting = listOf(bot.participant, "ee".repeat(32)), of = 4), 4)))
        // The two ticks take over from whatever the relays said, and show for a message sent from another device.
        assertEquals("Delivered to Ann $middleDot t1", line(TickState.NOT_KEPT, DeliveredTick(DeliveredView(listOf(ann), emptyList()), 1)))
        assertEquals("Delivered to Ann $middleDot t1", line(null, DeliveredTick(DeliveredView(listOf(ann), emptyList()), 1)))
    }

    @Test fun `only agents confirming count their latest time`() {
        val bot2 = DeliveredBy("ff".repeat(32), 30, true)
        assertEquals("Delivered to 2 $middleDot t30", line(TickState.SENT, DeliveredTick(DeliveredView(emptyList(), listOf(bot, bot2)), 2)))
        // People first: an agent's later confirmation does not move the time of the people's.
        assertEquals("Delivered to Ann $middleDot t1", line(TickState.SENT, DeliveredTick(DeliveredView(listOf(ann), listOf(bot2)), 1)))
    }

    @Test fun `a clock says sending`() {
        assertEquals("Sending\u2026", line(TickState.CHECKING))
        assertEquals("Sending\u2026", line(TickState.CHECKING, quiet = true))
    }

    @Test fun `one tick in a quiet room says only sent`() {
        assertEquals("Sent", line(TickState.SENT, quiet = true))
        assertEquals("Sent", line(TickState.SENT, quiet = true, soleOther = ann.participant))
    }

    @Test fun `one tick names the one other person, a fixed list of one or a link room with one known`() {
        // A fixed list with one other member, and a link room with one known person, both come as the sole other.
        assertEquals("Sent $middleDot no confirmation from Ann yet", line(TickState.SENT, soleOther = ann.participant))
        assertEquals("Sent $middleDot no confirmation from Clerk yet", line(TickState.SENT, soleOther = bot.participant))
    }

    @Test fun `one tick without exactly one other person says no confirmation yet`() {
        // A link room with two people known, a fixed list of several, or agents as well: no sole other.
        assertEquals("Sent $middleDot no confirmation yet", line(TickState.SENT, soleOther = null))
    }

    @Test fun `a message not kept, or with no tick and no confirmation, has no line`() {
        assertNull(line(TickState.NOT_KEPT))
        assertNull(line(TickState.NOT_KEPT, soleOther = ann.participant))
        assertNull(line(null))
        assertNull(line(null, quiet = true, soleOther = ann.participant))
    }

    @Test fun `the separator is a middle dot with a space either side, and no line says read or uses a dash`() {
        assertEquals(0xB7, middleDot[0].code)
        val lines = listOfNotNull(
            line(TickState.CHECKING), line(TickState.SENT), line(TickState.SENT, quiet = true), line(TickState.SENT, soleOther = ann.participant),
            line(TickState.SENT, DeliveredTick(DeliveredView(listOf(ann), emptyList()), 1)),
            line(TickState.SENT, DeliveredTick(DeliveredView(listOf(ann), emptyList(), waiting = listOf(bo.participant), of = 2), 2)))
        lines.forEach { text ->
            assertFalse(text.contains("read", ignoreCase = true), text)
            assertFalse(text.contains("\u2014") || text.contains("\u2013"), text)
            if (text.contains(middleDot)) assertTrue(text.contains(" $middleDot "), text)
        }
        // The one-tick lines do not claim anyone has it.
        assertFalse(line(TickState.SENT, soleOther = ann.participant)!!.contains("Delivered"))
    }
}
