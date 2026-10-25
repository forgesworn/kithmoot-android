package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * One room engine's use of discovered relays across this device's calls:
 * a login at each join, dropped at each leave. At most one attempt at a
 * time: the attempt is claimed before anything suspends, so a second join
 * signal cannot start a second [PublicRelayCall], and every check after a
 * suspension asks whether its attempt is still the current one.
 */
internal class PublicRelayCallRoute(
    private val selector: CallIceSelector,
    private val relays: PublicCallRelays,
    private val scope: CoroutineScope,
    private val search: suspend (Long) -> List<NostrEvent>,
    /** Not anonymous, no Spry pairing, switched on and a relay chosen. */
    private val mayRun: suspend () -> Boolean,
    private val pin: suspend (PublicRelayLogin) -> PinnedPublicRelay?,
    /** The session and this engine are still the open ones. */
    private val live: () -> Boolean,
    private val onCall: () -> Boolean,
    private val notice: (PublicRelayNotice) -> Unit,
    /** Takes this device off the call: relay-only never goes direct. */
    private val refuse: () -> Unit,
    /** The engine was built with no servers, waiting for a discovered relay at join (relay-only). */
    private val builtForDiscovered: Boolean,
    /** Relay-only: a login is being fetched, so the missing-relay line is held back until it lands or fails. */
    private val pending: (Boolean) -> Unit = {},
    private val callEnded: () -> Unit = PublicCallRelaysProcess::callEnded,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: Random = Random.Default,
) {
    private val lock = Any()
    private val attempt = AtomicReference<Any?>(null)
    @Volatile private var call: PublicRelayCall? = null
    private var starting: Job? = null
    private var wasOn = false
    private var closed = false

    init {
        // Spry took over: the discovered call stands down, and the attempt stays claimed so none restarts in this call.
        selector.onDiscoveredSuperseded = {
            synchronized(lock) {
                starting?.cancel()
                starting = null
                call?.end()
                call = null
                relays.dropLogins()
                pending(false)
            }
        }
    }

    /** Fed every value of "this device is on the call". Only a change counts: a room opening off a call ends nothing. */
    fun onCallChanged(on: Boolean) {
        synchronized(lock) {
            if (closed || on == wasOn) return
            wasOn = on
            if (on) start() else end()
        }
    }

    private fun start() {
        val token = Any()
        if (!attempt.compareAndSet(null, token)) return
        fun current() = attempt.get() === token && live() && onCall()
        starting = scope.launch {
            try {
                synchronized(lock) {
                    if (!current()) return@launch
                    if (selector.relayOnly) pending(true)
                }
                val may = selector.discoveredMayStart() && mayRun()
                if (!current()) return@launch
                if (!may) {
                    if (builtForDiscovered && selector.relayOnly && selector.current().servers == null) refuseWith(null)
                    return@launch
                }
                var noticed: PublicRelayNotice? = null
                val next = PublicRelayCall(relays, scope, search,
                    apply = { login ->
                        val pinned = if (login == null) null else pin(login)
                        // Checked and applied under the lock that [end] takes, so a relay never lands after the leave.
                        if (login != null && pinned == null) false
                        else synchronized(lock) { current() && selector.updateDiscovered(pinned) }
                    },
                    notice = { said -> noticed = said; if (current()) notice(said) },
                    relayOnly = selector.relayOnly,
                    superseded = { !current() || !selector.discoveredMayStart() },
                    clock = clock, random = random,
                )
                synchronized(lock) {
                    if (attempt.get() !== token) return@launch
                    call = next
                }
                if (!next.start()) {
                    synchronized(lock) { if (call === next) call = null }
                    if (current() && selector.relayOnly && selector.current().servers == null) refuseWith(noticed)
                }
            } finally {
                synchronized(lock) {
                    if (attempt.get() === token && selector.relayOnly) pending(false)
                }
            }
        }
    }

    /** Relay-only with no relay at join: "call relay lost" is always said, once, and the call is left. */
    private fun refuseWith(said: PublicRelayNotice?) {
        if (said == null) notice(PublicRelayNotice.START_FAILED_RELAY_ONLY)
        refuse()
    }

    /** This device left the call: the attempt, the discovered relay and every login go. */
    fun end() {
        synchronized(lock) {
            attempt.set(null)
            wasOn = false
            starting?.cancel()
            starting = null
            call?.end()
            call = null
            selector.callEnded()
            pending(false)
            callEnded()
        }
    }

    /** The engine is going (already detached from the selector). */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val had = attempt.get() != null || wasOn
            attempt.set(null)
            starting?.cancel()
            starting = null
            call?.end()
            call = null
            wasOn = false
            pending(false)
            if (had) callEnded()
        }
    }
}
