package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.media.PublicCallRelayMode
import java.time.ZoneId

/**
 * How a pairing, relay server or STUN setting reads from storage: nothing
 * saved, saved, or saved but unreadable. Kept apart from the stores so the
 * words are a pure function of these (the pattern of [CallRelayWords]).
 */
internal enum class StoredKind { NONE, SAVED, UNREADABLE }

/** Everything the Calls page and its root row are a function of (design-connections-calls, 6.1 and 6.2). */
internal data class CallPathFacts(
    /** The built-in relay's row second line ([CallRelayWords.page]); null when the phone has no such row. */
    val builtInRow: String? = null,
    /** The built-in relay is running now, so its pairing is this device's own Spry pairing. */
    val builtInRunning: Boolean = false,
    /** A hand-made Spry pairing for this account. */
    val spry: StoredKind = StoredKind.NONE,
    /** When this device's own Spry credential request last failed during a call, this run only. */
    val spryFailedAt: Long? = null,
    val server: StoredKind = StoredKind.NONE,
    /** The first address's host of a saved relay server. */
    val serverHost: String? = null,
    /** Public call relays, as the panel reports them; null before it has loaded. */
    val publicMode: PublicCallRelayMode = PublicCallRelayMode.OFF,
    val publicAnonymous: Boolean = false,
    val publicOutranked: Boolean = false,
    val publicChosen: Boolean = false,
    val hideIp: Boolean = false,
    /** The host of the first device STUN address; null when none is set. */
    val stunHost: String? = null,
    val stunUnreadable: Boolean = false,
    val nowMs: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** Which of the person's own relays carries their calls: the first ready one, in the order calls try them. */
internal enum class CallWinner { NONE, SERVER, SPRY, BUILT_IN, PUBLIC }

/** The Calls page as words. [spryFailed] is the optional third line (CS6). */
internal data class CallPathView(
    val winner: CallWinner,
    val head: String,
    val body: String,
    val spryFailed: String?,
    val spryRow: String,
    val serverRow: String,
    val publicRow: String,
    val advancedRow: String,
    /** Second line of the Calls row on Settings' list. */
    val rootSub: String,
)

/**
 * Every word of Settings › Calls and its sub-pages, in one place, as the web
 * keeps them in `call-path-words.ts`. Characters follow the spec: straight
 * apostrophes, "·" between a state and its reason. The order is the one
 * `CallIceSelector.choose()` already follows: relay server, then your Spry
 * (a paired node, or this phone's own relay while it runs, never both), then
 * the room's relay, then a public relay, then direct.
 */
internal object CallPathWords {
    const val ROOT = "Calls"
    const val TITLE = "Calls"
    const val SECTION = "Relays for your calls"
    const val ROW_SPRY = "Your Spry node"
    const val ROW_SERVER = "Relay server"
    const val ROW_PUBLIC = "Public call relays"
    const val ADVANCED = "Advanced"

    const val HIDE_IP = "Hide my IP address on calls"
    const val HIDE_IP_NOTE = "Calls always go through a relay, so people on the call don't see your IP address. " +
        "Calls can be slower, and KithMoot asks before using a relay someone in the room runs."
    const val ROOMS_NOTE = "Rooms can bring their own relay, named in the room's link or shared by a member. " +
        "Your calls use it when none of yours is in use, and before any public relay."

    // Summary (CS1 to CS5).
    private const val DIRECT_ONLY = "Direct only"
    private const val DIRECT_ONLY_BODY = "Calls connect device to device. Some networks block that, so a call may not connect. Set up a relay below to carry those calls."
    private const val NO_RELAY = "No relay for calls"
    private const val NO_RELAY_BODY = "Hide my IP address is on, so a call can only start in a room that brings its own relay. " +
        "Set up a relay below, or turn off Hide my IP address."
    private const val ALWAYS_BODY = "Hide my IP address is on, so calls never connect directly. People on the call don't see your IP address."
    private const val PUBLIC_BODY = "Calls connect device to device when they can. When they can't, they go through the public call relay you chose, unless the room brings its own."
    const val ROOT_NO_RELAY = "Hide my IP is on · no relay"

    /** What the winner is called in a sentence (9.2). */
    fun winnerName(winner: CallWinner): String = when (winner) {
        CallWinner.BUILT_IN -> "this phone's relay"
        CallWinner.SPRY -> "your Spry node"
        CallWinner.SERVER -> "your relay server"
        CallWinner.PUBLIC -> "a public relay"
        CallWinner.NONE -> ""
    }

    // Row second lines (6.2).
    const val SPRY_NONE = "Not paired"
    const val SPRY_PAIRED = "Paired"
    const val SPRY_SERVER_FIRST = "Paired · your relay server comes first"
    const val SPRY_UNREADABLE = "Can't be read · pair again"
    const val SERVER_NONE = "Not set up"
    const val SERVER_UNREADABLE = "Can't be read · save it again"
    const val PUBLIC_OFF = "Off"
    const val PUBLIC_OUTRANKED = "On · not used: you have your own relay"
    const val PUBLIC_NONE_CHOSEN = "On · none chosen"
    const val PUBLIC_ONE_CHOSEN = "On · one chosen"
    const val PUBLIC_ANONYMOUS = "Not available in anonymous mode"
    const val PUBLIC_UNREADABLE = "Can't be read"
    const val STUN_CLOUDFLARE = "STUN server: Cloudflare"
    const val STUN_UNREADABLE = "Can't be read · save it again"

    fun serverSet(host: String?) = if (host.isNullOrEmpty()) "Set up" else "Set up · $host"
    fun spryNoAnswer(failedAt: Long, nowMs: Long, zone: ZoneId) = "Paired · no answer " + CallRelayWords.atTime(failedAt, nowMs, zone)
    fun stunHost(host: String) = "STUN server: $host"

    // Sub-pages: Your Spry node.
    const val SPRY_TITLE = "Your Spry node"
    const val SPRY_LEAD = "Spry runs a call relay on your Archipelago node. Pair it once and your calls use it everywhere. Rooms you make share it with everyone in them."
    /** Android has no link field: Spry hands the pairing to the app, so the existing words stay. */
    const val SPRY_HOW = "Install Spry, then choose Pair with KithMoot."
    const val SPRY_ACCT_IN = "Your pairing is shared, encrypted, with your other devices signed in to this Nostr account."
    const val SPRY_ACCT_OUT = "The pairing stays on this device. Sign in with Nostr first to share it with your other devices."
    const val SPRY_EXCL = "While Spry is paired, the call relay on this phone stays off."
    const val SPRY_ON_HEAD = "Paired with your Spry node"
    fun spryHost(host: String) = "Paired with $host"
    const val SPRY_ON = "Your calls use it everywhere. Rooms you make share it. In other rooms, turn on Share my call relay here in the room's Privacy and connection."
    const val SPRY_SYNC = "Sync to my other devices"
    const val SPRY_UNPAIR = "Unpair…"
    const val SPRY_BAD_HEAD = "Your Spry pairing can't be read"
    const val SPRY_BAD = "Unpair it, then pair again."
    fun spryNoAnswerLine(failedAt: Long, nowMs: Long, zone: ZoneId, hideIp: Boolean): String =
        "Your Spry node didn't answer " + CallRelayWords.atTime(failedAt, nowMs, zone) +
            if (hideIp) ", so that call couldn't start. Check that Spry is running on your node."
            else ", so that call had no relay. Check that Spry is running on your node."

    // Sub-pages: Relay server.
    const val SRV_TITLE = "Relay server"
    const val SRV_LEAD = "A call relay you run or rent, for calls that can't connect directly. Set it on each device that needs it."
    const val SRV_ADDR = "Addresses"
    const val SRV_ADDR_HINT = "They start with turn: or turns:. Separate them with commas."
    const val SRV_USER = "Username"
    const val SRV_PASS = "Password"
    const val SRV_SHOW_PASS = "Show password"
    const val SRV_SAVE = "Save"
    const val SRV_REMOVE = "Remove…"
    fun srvOnHead(host: String?) = if (host.isNullOrEmpty()) "Set up" else "Set up: $host"
    const val SRV_ON = "Your calls use it when they can't connect directly."
    const val SRV_PRIVACY = "Saved on this device. The password never goes in room links. The relay sees your IP address and when and how much you call, not what's said."
    const val SRV_REOPEN = "After a change, leave and reopen a room to use it."
    const val SRV_SAVED = "Saved. Leave and reopen a room to use it."
    const val SRV_REMOVED = "Removed. Leave and reopen a room to stop using it."
    const val SRV_UNREADABLE = "The saved relay server can't be read. Save it again or remove it."
    const val SRV_RM_TITLE = "Remove this relay server?"
    const val SRV_RM_MSG = "Its address and login are deleted from this device. Your calls stop using it the next time you open a room."
    const val SRV_RM_BTN = "Remove"
    const val CANCEL = "Cancel"
    /** Own calls use the relay server while this phone's relay runs for others (T-OWNCALLS). */
    const val OWN_CALLS = "Your own calls use your relay server, which comes first. People in your rooms can still use this relay."

    // Sub-pages: Advanced.
    const val ADV_TITLE = "Advanced"
    const val ADV_STUN = "Your STUN server"
    const val ADV_STUN_NOTE = "STUN helps a call find a direct path. Calls use Cloudflare's public STUN server unless you enter your own, " +
        "for example stun:stun.example.org:3478. Kept on this device and never added to room links."
    const val ADV_SAVE = "Save"
    const val ADV_USE_DEFAULT = "Use the default"
    const val ADV_USING_DEFAULT = "Using Cloudflare's public STUN server."
    fun advUsing(host: String) = "Using your STUN server: $host."
    const val ADV_UNREADABLE = "The saved STUN server can't be read. Save it again or use the default."

    // Public call relays (the page's own words are in PublicCallRelaysCopy).

    private fun winner(f: CallPathFacts): CallWinner = when {
        f.server == StoredKind.SAVED -> CallWinner.SERVER
        f.spry == StoredKind.SAVED -> CallWinner.SPRY
        f.builtInRunning -> CallWinner.BUILT_IN
        f.publicMode == PublicCallRelayMode.ON && !f.publicAnonymous && !f.publicOutranked && f.publicChosen -> CallWinner.PUBLIC
        else -> CallWinner.NONE
    }

    private fun publicRow(f: CallPathFacts): String = when {
        f.publicAnonymous -> PUBLIC_ANONYMOUS
        f.publicMode == PublicCallRelayMode.UNAVAILABLE -> PUBLIC_UNREADABLE
        f.publicMode != PublicCallRelayMode.ON -> PUBLIC_OFF
        f.publicOutranked -> PUBLIC_OUTRANKED
        f.publicChosen -> PUBLIC_ONE_CHOSEN
        else -> PUBLIC_NONE_CHOSEN
    }

    private fun spryRow(f: CallPathFacts, winner: CallWinner): String = when {
        f.spry == StoredKind.NONE -> SPRY_NONE
        f.spry == StoredKind.UNREADABLE -> SPRY_UNREADABLE
        f.server == StoredKind.SAVED -> SPRY_SERVER_FIRST
        f.spryFailedAt != null && winner == CallWinner.SPRY -> spryNoAnswer(f.spryFailedAt, f.nowMs, f.zone)
        else -> SPRY_PAIRED
    }

    fun view(f: CallPathFacts): CallPathView {
        val winner = winner(f)
        val name = winnerName(winner)
        val (head, body) = when {
            f.hideIp && winner == CallWinner.NONE -> NO_RELAY to NO_RELAY_BODY
            f.hideIp -> "Always through $name" to ALWAYS_BODY
            winner == CallWinner.NONE -> DIRECT_ONLY to DIRECT_ONLY_BODY
            winner == CallWinner.PUBLIC -> "Direct, or through a public relay" to PUBLIC_BODY
            else -> "Direct, or through $name" to "Calls connect device to device when they can. When they can't, they go through $name."
        }
        val failed = if (winner == CallWinner.SPRY && f.spryFailedAt != null) spryNoAnswerLine(f.spryFailedAt, f.nowMs, f.zone, f.hideIp) else null
        return CallPathView(
            winner = winner, head = head, body = body, spryFailed = failed,
            spryRow = spryRow(f, winner),
            serverRow = when (f.server) {
                StoredKind.NONE -> SERVER_NONE
                StoredKind.SAVED -> serverSet(f.serverHost)
                StoredKind.UNREADABLE -> SERVER_UNREADABLE
            },
            publicRow = publicRow(f),
            advancedRow = when {
                f.stunUnreadable -> STUN_UNREADABLE
                f.stunHost != null -> stunHost(f.stunHost)
                else -> STUN_CLOUDFLARE
            },
            rootSub = if (f.hideIp && winner == CallWinner.NONE) ROOT_NO_RELAY else head,
        )
    }

    /** The host of a relay address such as `turn:relay.example.org:3478?transport=tcp`, for "Set up · host". */
    fun hostOf(address: String): String? =
        Regex("^(?:turns?|stuns?):(\\[[0-9a-f:.]+\\]|[^:?/\\s]+)", RegexOption.IGNORE_CASE).find(address.trim())?.groupValues?.get(1)
}
