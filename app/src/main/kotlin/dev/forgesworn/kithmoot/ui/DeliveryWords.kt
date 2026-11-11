package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.session.DeliveredTick
import dev.forgesworn.kithmoot.session.DeliveredView
import dev.forgesworn.kithmoot.session.TickState

/**
 * The words of the ticks beside the time on this person's own messages
 * (delivery ticks design, "UI"). They match the web client's `deliveryWords`
 * in `app/src/delivery-state.ts` and `deliveredWords` in
 * `app/src/delivery-receipts.ts`: [Words.label] is its `aria-label` and the
 * `contentDescription` here, [Words.title] its tooltip and the words shown
 * here. The pending row's words (`pendingStatus` in ChatPane.kt) are separate
 * and unchanged. Nothing here says read: there is no tick for that.
 *
 * The ticks are drawn in the bubble's full-strength ink, outline only and
 * never a highlight colour. This person's newest message also carries a line
 * of words under its bubble, [statusLine], saying who has it.
 */
internal object DeliveryWords {
    data class Words(val label: String, val title: String)

    const val RETRY = "Retry"

    fun of(state: TickState): Words = when (state) {
        TickState.CHECKING -> Words("Sending", "Sending…")
        TickState.SENT -> Words("Sent", "Sent. Not yet confirmed on anyone else’s device.")
        TickState.NOT_KEPT -> Words("Not kept. Retry", "Not kept: a relay accepted it but did not keep it.")
    }

    /**
     * Two ticks: what they say, and their label. A room with a fixed member
     * list says "of N"; a link room, where nobody holds the guest list, never
     * does. One person confirming in a room of two is named in the label.
     * [knownOthers] is everybody besides this person known in the room.
     */
    fun delivered(view: DeliveredView, nameOf: (String) -> String, knownOthers: Int): Words {
        val count = if (view.people.isNotEmpty()) view.people.size else view.agents.size
        val first = view.people.firstOrNull() ?: view.agents.first()
        val of = view.of
        if (of != null) {
            if (of <= 1) return Words("Delivered to ${nameOf(first.participant)}", "Delivered")
            return "Delivered to $count of $of".let { Words(it, it) }
        }
        if (count == 1 && knownOthers <= 1) return Words("Delivered to ${nameOf(first.participant)}", "Delivered")
        return "Delivered to $count".let { Words(it, it) }
    }

    /**
     * The line of words under this person's newest own message, saying who has it; null for no line.
     * Not a live region: a change is not announced.
     *
     * - Two ticks ([confirmed]): the label of [delivered], then the latest confirmation's time:
     *   "Delivered to Ann · 14:02", "Delivered to 2 of 4 · 14:02".
     * - A clock ([TickState.CHECKING]): "Sending…".
     * - One tick ([TickState.SENT]): "Sent" in a [quiet] room, which has nobody's confirmation to wait
     *   for; "Sent · no confirmation from Ann yet" where [soleOther] is the one other person the room
     *   has (`soleOtherPerson` in session/DeliveredLedger.kt); else "Sent · no confirmation yet".
     * - [TickState.NOT_KEPT]: none, since the line under the bubble that offers Retry already says it.
     * - No tick and no confirmation, as for a message sent from another of this person's devices: none.
     *
     * [clock] formats a confirmation's unix seconds the way the message's own time is shown.
     */
    fun statusLine(
        tick: TickState?,
        confirmed: DeliveredTick?,
        nameOf: (String) -> String,
        quiet: Boolean,
        soleOther: String?,
        clock: (Long) -> String,
    ): String? {
        if (confirmed != null) {
            val by = confirmed.view.people.ifEmpty { confirmed.view.agents }
            if (by.isNotEmpty()) return delivered(confirmed.view, nameOf, confirmed.knownOthers).label + SEPARATOR + clock(by.maxOf { it.at })
        }
        return when (tick) {
            null, TickState.NOT_KEPT -> null
            TickState.CHECKING -> SENDING_LINE
            TickState.SENT -> when {
                quiet -> SENT_LINE
                soleOther != null -> "$SENT_LINE${SEPARATOR}no confirmation from ${nameOf(soleOther)} yet"
                else -> "$SENT_LINE${SEPARATOR}no confirmation yet"
            }
        }
    }

    private const val SEPARATOR = " \u00B7 "
    private const val SENT_LINE = "Sent"
    private const val SENDING_LINE = "Sending…"

    /** The heading of the details sheet: the words when they count against a list, else the label. */
    fun deliveredHeading(view: DeliveredView, words: Words): String = if ((view.of ?: 0) > 1) words.title else words.label

    /** What opening the two ticks does, for a screen reader. */
    const val DELIVERED_OPEN = "Show who it was delivered to"
    const val DELIVERED_DETAILS = "Delivery details"
    const val AGENTS = "Agents"
    const val NOT_CONFIRMED_YET = "Not confirmed yet"
    /** Under every list of who confirmed. */
    const val DELIVERED_FOOTNOTE = "Delivered means their device received it. It does not mean they read it."
    /** Added in a link room, where nobody holds the guest list. */
    const val DELIVERED_LINK_ROOM_NOTE = "People not listed may be away, or using an app that does not confirm delivery."

    /** Settings › Privacy: the web's Messages › Delivery. */
    const val SETTING_SECTION = "Delivery"
    const val SETTING_LABEL = "Confirm when messages reach this device"
    const val SETTING_SUMMARY = "Senders see two ticks once a message reaches your device. This never says whether you read it. " +
        "Turned off, this device sends no confirmations; you still see them from other people. Quiet chats never send them. Saved on this device."
}
