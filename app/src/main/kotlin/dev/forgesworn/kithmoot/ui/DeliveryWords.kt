package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.session.TickState

/**
 * The words of the tick beside the time on this device's own messages
 * (delivery ticks design, "UI"). They match the web client's `deliveryWords`
 * in `app/src/delivery-state.ts`: [label] is its `aria-label` and the
 * `contentDescription` here, [title] its tooltip and the words shown here.
 * The pending row's words (`pendingStatus` in ChatPane.kt) are separate and
 * unchanged. Nothing here says read: there is no tick for that.
 */
internal object DeliveryWords {
    data class Words(val label: String, val title: String)

    const val RETRY = "Retry"

    fun of(state: TickState): Words = when (state) {
        TickState.CHECKING -> Words("Sending", "Sending…")
        TickState.SENT -> Words("Sent", "Sent. Not yet confirmed on anyone else’s device.")
        TickState.NOT_KEPT -> Words("Not kept. Retry", "Not kept: a relay accepted it but did not keep it.")
    }
}
