package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.TickState
import dev.forgesworn.kithmoot.ui.DeliveryWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The words match the web client's `deliveryWords` in `app/src/delivery-state.ts`. */
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
}
