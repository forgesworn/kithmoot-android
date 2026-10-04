package dev.forgesworn.kithmoot.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.random.asKotlinRandom

/**
 * Holds back the account's relay publishes while a Tor-only room is open, and
 * for a random few minutes after the last one closes (P6-01, the owner's
 * decision of 4 October; vennel `docs/gate/2026-10-04-account-sync-in-tor-only-rooms.md`).
 *
 * The account's connections stay up: stopping them would show its relays when
 * the room opened and closed. Only publishing waits, so nothing the account
 * sends lines up with the room. A person's own account action ends the wait
 * after a close, never while a Tor-only room is open. Signing is not held: a
 * bunker still hears a signing request when one is made.
 *
 * Counted and process-wide: the call's instance owns account sync, while a
 * visited chat-only instance may be the one in the Tor-only room.
 */
class AccountWriteHold(
    private val scope: CoroutineScope,
    private val afterClose: () -> Long = { MIN_WAIT_MS + random.nextLong(MAX_WAIT_MS - MIN_WAIT_MS + 1) },
) {
    private var open = 0
    private var release: Job? = null
    private val held = MutableStateFlow(false)

    val isHeld: Boolean get() = held.value

    fun torOnlyRoomOpened() {
        synchronized(this) { open++; release?.cancel(); release = null; held.value = true }
    }

    fun torOnlyRoomClosed() {
        synchronized(this) {
            if (open == 0) return
            if (--open > 0) return
            val wait = afterClose()
            release = scope.launch {
                delay(wait)
                // A timer already past its delay must not end a hold that a reopen and a later close started again.
                synchronized(this@AccountWriteHold) {
                    if (open == 0 && release === coroutineContext.job) { held.value = false; release = null }
                }
            }
        }
    }

    /** A person's own account action ends the wait after a close. */
    fun personActed() {
        synchronized(this) {
            if (open > 0) return
            release?.cancel(); release = null; held.value = false
        }
    }

    suspend fun awaitReleased() { held.first { !it } }

    companion object {
        const val MIN_WAIT_MS = 3 * 60_000L
        const val MAX_WAIT_MS = 10 * 60_000L
        private val random = java.security.SecureRandom().asKotlinRandom()
        val process = AccountWriteHold(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
