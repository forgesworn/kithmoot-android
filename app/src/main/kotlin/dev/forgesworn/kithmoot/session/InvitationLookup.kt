package dev.forgesworn.kithmoot.session

/**
 * Where to look for a group invitation the link's own relays no longer hold.
 *
 * A persistent link names the relays its invitation was published to when the
 * link was made. Public relays drop that event within days, and a room that
 * has since moved, or a creator who re-signs it on the room's current relays,
 * leaves the copy somewhere the link does not point. A person who added one of
 * those relays by hand got straight in, so the door asks the relays this
 * device already uses, and the app's defaults, before saying the invitation
 * cannot be found.
 *
 * Harmless to ask anywhere: the invitation is signed by the link's inviter key
 * and encrypted to the link, so whichever relay answers cannot forge or read
 * it, and a retirement notice found there still wins. The one place this never
 * goes is out of a sheltered room: a link that names a circle relay was kept
 * off public relays on purpose. Mirrors `app/src/invitation-lookup.ts` in the
 * web client.
 */

/** The admission failed because no relay asked had the invitation, not
 *  because it was retired, ended or malformed. */
fun isMissingInvitation(error: Throwable): Boolean = error is MissingGroupInvitationException

private fun relayKey(url: String): String = url.trim().lowercase().trimEnd('/')

/**
 * Relays worth asking after the link's own: this device's, then the app's
 * defaults, without the link's relays or any circle relay, and none at all
 * for a link that names a circle relay.
 */
fun widerInvitationRelays(
    link: List<String>,
    own: List<String>,
    defaults: List<String>,
    isCircle: (String) -> Boolean,
): List<String> {
    if (link.any(isCircle)) return emptyList()
    val seen = link.mapTo(mutableSetOf(), ::relayKey)
    val wider = mutableListOf<String>()
    for (url in own + defaults) {
        val key = relayKey(url)
        if (key in seen || isCircle(url) || !(key.startsWith("wss://") || key.startsWith("ws://"))) continue
        seen += key
        wider += url
    }
    return wider
}

/** The relays a link names that the room no longer uses, where a re-signed
 *  invitation also has to go so older copies of the link still find it.
 *  Circle relays are left to the room's own connection, which can
 *  authenticate to them. */
fun linkOnlyRelays(room: List<String>, link: List<String>, isCircle: (String) -> Boolean): List<String> {
    val seen = room.mapTo(mutableSetOf(), ::relayKey)
    val out = mutableListOf<String>()
    for (url in link) {
        val key = relayKey(url)
        if (key in seen || isCircle(url)) continue
        seen += key
        out += url
    }
    return out
}
