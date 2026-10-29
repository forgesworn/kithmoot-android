package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.DeliveredBy
import dev.forgesworn.kithmoot.session.DeliveredView
import dev.forgesworn.kithmoot.session.TickState
import dev.forgesworn.kithmoot.ui.DeliveryWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
