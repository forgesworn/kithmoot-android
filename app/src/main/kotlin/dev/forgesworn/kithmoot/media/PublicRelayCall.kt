package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What a person is told about a discovered relay during a call. */
internal enum class PublicRelayNotice(val text: String) {
    START_FAILED("The public call relay you chose did not answer, so this call uses the room’s usual connection."),
    START_FAILED_RELAY_ONLY("Call relay lost: the public call relay you chose did not answer. You chose to hide your IP address, so this call will not switch to a direct connection."),
    RENEW_FAILED("The public call relay did not renew its login. The call may lose its relay when the current login ends."),
    LOST("Call relay lost: the public call relay’s login ended and could not be renewed. The call carries on without it where a direct connection works."),
    LOST_RELAY_ONLY("Call relay lost: the public call relay’s login ended and could not be renewed. You chose to hide your IP address, so this call will not switch to a direct connection."),
}

/**
 * One call's use of a discovered relay. Started when this device joins the
 * call, renewed before its login ends while the call lasts, dropped when the
 * login lapses or the call ends. Never fails over to anything but the relays
 * this person chose, never turns a relay-only call direct, and never searches
 * again once started.
 */
internal class PublicRelayCall(
    private val relays: PublicCallRelays,
    private val scope: CoroutineScope,
    /** The discovery query, used at most once, at start, when a chosen relay's advert is not in hand. */
    private val search: suspend (Long) -> List<dev.forgesworn.kithmoot.protocol.NostrEvent>,
    /** Pins and applies a login, or (null) removes the relay. False when the login could not be applied. */
    private val apply: suspend (PublicRelayLogin?) -> Boolean,
    private val notice: (PublicRelayNotice) -> Unit,
    private val relayOnly: Boolean,
    /** Whether something with higher precedence (Spry) has taken over. */
    private val superseded: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: Random = Random.Default,
) {
    @Volatile private var ended = false
    @Volatile var login: PublicRelayLogin? = null
        private set
    private var renewal: Job? = null
    private var lapse: Job? = null

    val active: Boolean get() = login != null

    /**
     * Tries the chosen relays in order. A relay whose advert is not in hand
     * costs one discovery query for the whole start, never one per relay.
     * False, with the notice given, when none could be used.
     */
    suspend fun start(): Boolean {
        val candidates = relays.callCandidates()
        if (ended || candidates.isEmpty()) return false
        var searched = false
        for (key in candidates) {
            if (ended || superseded()) return false
            if (relays.advert(key) == null && !searched) {
                searched = true
                try { relays.discover(search) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { }
                if (ended) return false
            }
            if (relays.advert(key) == null) continue
            val result = relays.login(key)
            if (ended || superseded()) return false
            val issued = (result as? PublicRelayLoginResult.Issued)?.login ?: continue
            if (!apply(issued)) { relays.dropLogin(key); continue }
            if (ended || superseded()) return false
            login = issued
            relays.markGood(key)
            schedule(issued)
            return true
        }
        if (!ended && !superseded()) notice(if (relayOnly) PublicRelayNotice.START_FAILED_RELAY_ONLY else PublicRelayNotice.START_FAILED)
        return false
    }

    private fun schedule(current: PublicRelayLogin) {
        renewal?.cancel()
        lapse?.cancel()
        val now = clock()
        lapse = scope.launch {
            delay(((current.expiresAt - now) * 1000).coerceAtLeast(0))
            lost(current)
        }
        renewal = scope.launch {
            // Ten minutes before it ends, with up to 30 s of jitter so a room's devices do not ask at once.
            val at = (current.expiresAt - RENEW_AHEAD_SECONDS - now).coerceAtLeast(0) * 1000
            delay(at + random.nextLong(JITTER_MS))
            renew(current)
        }
    }

    /** One fresh login per renewal, applied once: one ICE restart on every link, not one per retry. */
    private suspend fun renew(current: PublicRelayLogin) {
        var wait = FIRST_RETRY_MS
        while (true) {
            if (ended || login !== current || superseded()) return
            when (val result = relays.login(current.key, fresh = true)) {
                is PublicRelayLoginResult.Issued -> {
                    if (ended || login !== current || superseded()) return
                    if (!apply(result.login)) { notice(PublicRelayNotice.RENEW_FAILED); return }
                    login = result.login
                    schedule(result.login)
                    return
                }
                PublicRelayLoginResult.Busy -> {
                    // Retried only while a retry can still land before the login ends.
                    if (clock() + wait / 1000 >= current.expiresAt - DEADLINE_MARGIN_SECONDS) {
                        if (!ended && login === current) notice(PublicRelayNotice.RENEW_FAILED)
                        return
                    }
                    delay(wait)
                    wait *= 2
                }
                PublicRelayLoginResult.Failed -> {
                    if (!ended && login === current) notice(PublicRelayNotice.RENEW_FAILED)
                    return
                }
            }
        }
    }

    private suspend fun lost(current: PublicRelayLogin) {
        if (ended || login !== current) return
        login = null
        renewal?.cancel()
        relays.dropLogin(current.key)
        apply(null)
        notice(if (relayOnly) PublicRelayNotice.LOST_RELAY_ONLY else PublicRelayNotice.LOST)
    }

    /** The call ended, or Spry took over: timers stop and the login is dropped from memory. */
    fun end() {
        ended = true
        renewal?.cancel()
        lapse?.cancel()
        login?.let { relays.dropLogin(it.key) }
        login = null
    }

    companion object {
        const val RENEW_AHEAD_SECONDS = 600L
        const val JITTER_MS = 30_000L
        const val FIRST_RETRY_MS = 10_000L
        const val DEADLINE_MARGIN_SECONDS = 30L
    }
}
