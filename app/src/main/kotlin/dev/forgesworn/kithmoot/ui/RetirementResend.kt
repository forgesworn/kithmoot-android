package dev.forgesworn.kithmoot.ui

/**
 * Which relays still need each of a room's signed retirements, as relays
 * connect.
 *
 * The open room used to publish every retirement to every write relay on
 * each change of the connected set, so one relay bouncing re-sent up to 128
 * events to all of them. Now each retirement goes once per session to each
 * relay that has not said OK to it. A relay that holds this phone off keeps
 * the offer queued until it lifts (see `relay/RelayGate.kt`), and one that
 * refused it for good is never offered it again, so once is enough.
 */
internal class RetirementResend {
    private val offered = mutableMapOf<String, MutableSet<String>>()

    /** Retirement id to the relays among [up] it should go to now. Marks them offered. */
    fun due(up: Set<String>, retirements: List<String>, acknowledged: (String) -> Set<String>): Map<String, Set<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        for (id in retirements.distinct()) {
            val acked = acknowledged(id)
            for (url in up) {
                if (url in acked) continue
                if (offered.getOrPut(id) { mutableSetOf() }.add(url)) out.getOrPut(id) { linkedSetOf() } += url
            }
        }
        return out
    }
}
