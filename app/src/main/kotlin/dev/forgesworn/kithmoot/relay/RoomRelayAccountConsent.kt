package dev.forgesworn.kithmoot.relay

/** Foreground room permission, held in memory for one account session only.
 * Relay lists, invitations and circle labels never populate it. */
class RoomRelayAccountConsent {
    private var owner: Any? = null
    private var granted = emptySet<String>()
    var epoch: Long = 0
        private set

    @Synchronized fun accountChanged(session: Any?) {
        if (owner === session) return
        owner = session
        granted = emptySet()
        epoch++
    }

    @Synchronized fun change(expectedEpoch: Long, url: String, enabled: Boolean) {
        check(owner != null && expectedEpoch == epoch) { "The account changed. Choose relay authentication again." }
        val clean = RelaySelection.validate(listOf(RelayChoice(url))).single().url
        granted = if (enabled) granted + clean else granted - clean
    }

    @Synchronized fun permits(session: Any?, url: String): Boolean =
        session != null && session === owner && url.removeSuffix("/") in granted

    @Synchronized fun retain(urls: Set<String>) { granted = granted.intersect(urls.map { it.removeSuffix("/") }.toSet()) }
    @Synchronized fun urls(): Set<String> = granted.toSet()
}
