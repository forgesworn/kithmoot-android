package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.media.PublicCallRelayMode
import dev.forgesworn.kithmoot.media.PublicCallRelays
import dev.forgesworn.kithmoot.media.PublicRelayListing
import dev.forgesworn.kithmoot.media.PublicRelayLogin
import dev.forgesworn.kithmoot.media.PublicRelayLoginResult
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Connections words for public call relays: the web's, except where Android does something different. */
internal object PublicCallRelaysCopy {
    const val TITLE = "Public call relays"
    const val SWITCH = "Find public call relays on my Nostr relays"
    const val NOTE = "Off unless you turn it on. The app asks the Nostr relays in your Connections list for adverts that relay operators publish themselves; each of those relays learns your IP address, the time and that a search for call relays was made. Public call relays are run by strangers. KithMoot runs no list of them and does not check, rank or vouch for anyone listed. A relay you use sees your IP address and your call’s timing and volume, but not its content."
    const val BLOCKED = "Your Spry pairing or your own call relay is used for calls, so public call relays are not looked up."
    const val ANONYMOUS = "Not available in anonymous mode: a call relay cannot be reached over Tor, and the search and login would go out in the clear."
    const val UNREADABLE = "The saved public call relay settings could not be read on this device, so nothing is looked up. Clearing them turns this off and forgets them."
    const val CLEAR_UNREADABLE = "Clear and turn off"
    const val IN_CALL = "Finish the call to look for or check public call relays."
    const val LOOK_AGAIN = "Look again"
    const val CLAIMS = "Region and notes are what each operator says about itself. Nothing here is checked."
    // Android sends no app name, so the web's "and which web app asked" is not said here.
    const val CHECK_NOTE = "Checking contacts only the relay you selected: its operator learns your IP address and the time, and issues an hour-long login kept on this device for that check and your next call. No camera or microphone is used."
    const val CHECK = "Check and use the selected relay"
    const val FORGET = "Stop using a public relay"
    const val SEARCHING = "Asking your Nostr relays for public call relays…"
    const val NONE_FOUND = "No public call relays were found on your Nostr relays."
    const val NOT_ASKED = "Your Nostr relays could not be asked. Try again later."
    const val SELECTED = "Selected for your calls on this visit. It is remembered on this device once a check or a call through it works."
    // Android's check asks the relay for an address rather than sending test data through it.
    const val CHECKING = "Asking the selected relay for a login and a relay address…"
    const val PASSED = "The relay gave this phone a relay address with its login. Your calls will use it when nothing you set yourself or the room’s own relay comes first."
    const val FAILED = "The check did not complete. The relay may be busy or unreachable from this network."
    const val STOPPED = "Relay check stopped."
    const val TURNED_OFF = "Turned off. Everything it kept on this device has been cleared."
    const val NOT_SAVED = "The change could not be saved on this device. Try again."
    const val FORGOTTEN = "No public call relay is chosen. Calls use the room’s usual connection."
    const val LAST_WORKED = " · last worked on this device"
    const val NEW_KEY = "This address worked before under a different key. It may not be the same operator."

    fun found(count: Int) = "$count public call relay${if (count == 1) "" else "s"} found. Select one to use it."
    fun key(entry: PublicRelayListing) = "Key ${entry.shortKey}${if (entry.lastGood) LAST_WORKED else ""}"

    /** The operator's own words, isolated so they cannot reorder what is around them. */
    fun claim(entry: PublicRelayListing): String? {
        val said = listOfNotNull(entry.region?.let { "region $it" }, entry.note).joinToString(" · ")
        return if (said.isEmpty()) null else "The operator says: \u2068$said\u2069"
    }
}

internal data class PublicCallRelaysView(
    val loaded: Boolean = false,
    val mode: PublicCallRelayMode = PublicCallRelayMode.OFF,
    val anonymous: Boolean = false,
    val outranked: Boolean = false,
    val inCall: Boolean = false,
    val listing: List<PublicRelayListing> = emptyList(),
    val selected: String? = null,
    val searching: Boolean = false,
    val checking: Boolean = false,
    val canForget: Boolean = false,
    val status: String = "",
) {
    val switchOn: Boolean get() = mode == PublicCallRelayMode.ON && !anonymous
    val switchEnabled: Boolean get() = loaded && !anonymous && mode != PublicCallRelayMode.UNAVAILABLE && !checking
    val pickerShown: Boolean get() = switchOn && !outranked
    val canCheck: Boolean get() = pickerShown && !inCall && !checking && selected != null && listing.any { it.key == selected }
}

/**
 * Settings' side of public call relays: the switch, one discovery query when
 * the page opens or the person asks again, the picker and the check. Nothing
 * runs in anonymous mode, with a Spry pairing or a hand-entered relay, or
 * while this device is on a call.
 */
class PublicCallRelaysPanel internal constructor(
    private val relays: PublicCallRelays,
    private val scope: CoroutineScope,
    private val search: suspend (Long) -> List<NostrEvent>,
    /** A Spry pairing or a hand-entered relay is present (or cannot be read). */
    private val outranked: suspend () -> Boolean,
    private val anonymous: () -> Boolean,
    private val inCall: () -> Boolean,
    /** Pins the login's address and runs the relay check. */
    private val check: suspend (PublicRelayLogin) -> Boolean,
) {
    private val _view = MutableStateFlow(PublicCallRelaysView())
    internal val view: StateFlow<PublicCallRelaysView> = _view.asStateFlow()
    private var work: Job? = null

    private suspend fun refreshFacts() {
        val mode = try { relays.load() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { PublicCallRelayMode.UNAVAILABLE }
        val blocked = try { outranked() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { true }
        _view.update { it.copy(loaded = true, mode = mode, anonymous = anonymous(), outranked = blocked, inCall = inCall()) }
        render()
    }

    private fun render() = _view.update { view ->
        val listing = if (view.pickerShown) relays.listing() else emptyList()
        view.copy(listing = listing, selected = relays.selected ?: relays.lastGood?.key, canForget = relays.lastGood != null || relays.selected != null)
    }

    private fun mayAsk(): Boolean { val v = _view.value; return v.pickerShown && !v.inCall }

    /** The page opened: read the facts, then one query unless one ran in the last minute. */
    internal fun opened() {
        stop(false)
        work = scope.launch {
            refreshFacts()
            if (mayAsk() && System.currentTimeMillis() - relays.searchedAt >= 60_000) find()
        }
    }

    /** For the Connections row: the facts only, with no query. */
    internal fun peek() { if (work?.isActive != true) scope.launch { refreshFacts() } }

    /** The page closed: a check in progress is stopped. */
    internal fun closed() = stop(true)

    private fun stop(say: Boolean) {
        val running = work
        work = null
        running?.cancel()
        if (_view.value.checking || _view.value.searching) _view.update { it.copy(checking = false, searching = false, status = if (say && it.checking) PublicCallRelaysCopy.STOPPED else it.status) }
    }

    private suspend fun find() {
        if (!mayAsk()) return
        _view.update { it.copy(searching = true, status = PublicCallRelaysCopy.SEARCHING) }
        val status = try {
            val found = relays.discover(search)
            if (found.isEmpty()) PublicCallRelaysCopy.NONE_FOUND else PublicCallRelaysCopy.found(found.size)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { PublicCallRelaysCopy.NOT_ASKED }
        _view.update { it.copy(searching = false, status = status) }
        render()
    }

    internal fun lookAgain() {
        stop(true)
        work = scope.launch { refreshFacts(); find() }
    }

    internal fun setEnabled(on: Boolean) {
        stop(true)
        work = scope.launch {
            if (on && (anonymous() || _view.value.mode == PublicCallRelayMode.UNAVAILABLE)) return@launch refreshFacts()
            val saved = try { relays.setEnabled(on); true } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            _view.update { it.copy(status = if (!saved) PublicCallRelaysCopy.NOT_SAVED else if (on) "" else PublicCallRelaysCopy.TURNED_OFF) }
            refreshFacts()
            if (on && saved) find()
        }
    }

    /** An unreadable file can only be cleared, which is the same as turning the switch off. */
    internal fun clearUnreadable() = setEnabled(false)

    internal fun select(key: String) {
        stop(true)
        relays.select(key)
        _view.update { it.copy(status = PublicCallRelaysCopy.SELECTED) }
        render()
    }

    internal fun forget() {
        stop(true)
        work = scope.launch {
            try { relays.forgetChoice() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            _view.update { it.copy(status = PublicCallRelaysCopy.FORGOTTEN) }
            render()
        }
    }

    internal fun checkSelected() {
        stop(true)
        work = scope.launch {
            refreshFacts()
            val key = relays.selected ?: relays.lastGood?.key
            if (key == null || !_view.value.canCheck) return@launch
            _view.update { it.copy(checking = true, status = PublicCallRelaysCopy.CHECKING) }
            val passed = try {
                // The next call counts this check among its three operators.
                if (!inCall()) relays.newBudget()
                val login = (relays.login(key) as? PublicRelayLoginResult.Issued)?.login
                login != null && check(login)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (passed) { relays.select(key); relays.markGood(key) }
            _view.update { it.copy(checking = false, status = if (passed) PublicCallRelaysCopy.PASSED else PublicCallRelaysCopy.FAILED) }
            render()
        }
    }
}
