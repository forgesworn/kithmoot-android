package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.service.ReachabilityBanner
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the debug-only "Restore witness" screen shows for the signed-in persona (C5). */
data class RestoreWitnessState(
    val persona: String? = null,
    val enrolment: WitnessEnrolment? = null,
    val status: CoordinationStatus? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The keeper's enrolment of a persona at their box (B3), and the app's side of
 * it while it runs: the "Restore witness" screen, the pending banner and the
 * foreground retiring timer. Debug builds only: release builds get no
 * [RestoreWitness] at all (`restoreWitness()` answers null there).
 *
 * [quiet] is shared with the persona's [PersonaLinks]: true while a Tor-only
 * room is open, so no witness traffic leaves the phone (C7).
 */
class RestoreWitness(
    private val vault: MlsVault,
    private val links: PersonaLinks,
    val quiet: AtomicBoolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) {
    private val _state = MutableStateFlow(RestoreWitnessState())
    val state: StateFlow<RestoreWitnessState> = _state.asStateFlow()

    /** The signed-in persona's status for the banner: null when it has no coordinated state on this phone. */
    private val _banner = MutableStateFlow<CoordinationStatus?>(null)
    val banner: StateFlow<CoordinationStatus?> = _banner.asStateFlow()

    /** One action at a time: each holds the persona lock across its witness trip anyway. */
    private val acting = Mutex()

    /** The screen opened for [persona] (or none signed in): reads where enrolment stands. */
    fun open(persona: String?) = act(persona) { refresh(it, check = false) }

    /** Mints the persona's installation id and writer before any pairing. */
    fun prepare(persona: String) = act(persona) { p ->
        vault.prepareCoordination(p).refusedAs("This account's vault cannot start a new enrolment yet.")
        refresh(p, check = false)
    }

    /** Scans the keeper's `bothyd witness pair` code into the persona's own Link engine. */
    fun pair(persona: String, code: String) = act(persona) { p ->
        check(!quiet.get()) { "Close the Tor-only room first: witness traffic is paused while it is open." }
        val pairing = try { BothyPairing.parse(code.trim(), now()) } catch (error: IllegalArgumentException) {
            throw IllegalStateException(error.message ?: "The pairing code is not valid.")
        }
        vault.pairWitness(p) { seed ->
            try { links.pair(p, seed, pairing).await() } catch (error: ExecutionException) { throw error.cause ?: error }
        }.refusedAs("This account cannot pair a witness now.")
        refresh(p, check = false)
    }

    /** Genesis over the empty persona, pinned to the paired box; the keeper is then shown the enrol line. */
    fun begin(persona: String) = act(persona) { p ->
        val subject = ByteArray(32).also(random::nextBytes)
        vault.beginCoordination(p, subject).refusedAs("Pair with the box first.")
        refresh(p, check = false)
    }

    /** "Check now": asks the witness again. */
    fun checkNow(persona: String) = act(persona) { refresh(it, check = true) }

    /** Replaces this installation: the keeper must then retire its subject at the box. */
    fun replace(persona: String) = act(persona) { p ->
        vault.clear(p)
        refresh(p, check = false)
    }

    /** The keeper says they ran the retire line for [subject]: only then may the persona enrol afresh. */
    fun keeperRetired(persona: String, subject: String) = act(persona) { p ->
        vault.keeperConfirmsRetired(p, subject).refusedAs("Not yet: the box has not finished retiring this phone's old writer.")
        links.forget(p)
        refresh(p, check = false)
    }

    /**
     * The foreground timer's tick: every persona's retiring duty, then the
     * banner for [persona]. Failures leave the banner as it was.
     */
    suspend fun foregroundTick(persona: String?) {
        if (quiet.get()) return
        runCatching { vault.runRetiringDuties() }
        refreshBanner(persona)
    }

    private suspend fun refreshBanner(persona: String?) {
        _banner.value = try {
            if (persona == null || !vault.coordinationKnown(persona)) null else vault.coordinationStatus(persona)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _banner.value
        }
    }

    private suspend fun refresh(persona: String?, check: Boolean) {
        if (persona == null) {
            _state.value = RestoreWitnessState()
            _banner.value = null
            return
        }
        val enrolment = vault.witnessEnrolment(persona)
        val status = if (enrolment is WitnessEnrolment.Enrolled || enrolment is WitnessEnrolment.Fenced) vault.coordinationStatus(persona, check) else null
        _state.update { it.copy(persona = persona, enrolment = enrolment, status = status) }
        _banner.value = status
    }

    private fun act(persona: String?, work: suspend (String) -> Unit) {
        scope.launch {
            acting.withLock {
                _state.update { it.copy(busy = true, error = null) }
                try {
                    if (persona == null) refresh(null, false) else work(persona)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.update { it.copy(error = describe(error)) }
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun <T> VaultResult<T>.refusedAs(message: String): T = when (this) {
        is VaultResult.Ok -> value
        is VaultResult.Refused -> throw IllegalStateException(message)
    }

    private companion object {
        /** Words for the person; never a stack trace or a secret. */
        fun describe(error: Exception): String = when (error) {
            is IllegalStateException -> error.message ?: "That did not work."
            is MlsVaultUnavailableException -> "This account's vault could not be read or written."
            else -> "The box could not be reached for pairing (${error.javaClass.simpleName})."
        }
    }
}

/**
 * The pending banner (C5), or null when there is nothing to say: only a
 * persona with coordinated state that the witness does not confirm.
 */
fun witnessBanner(status: CoordinationStatus?): ReachabilityBanner? = when (status) {
    null, CoordinationStatus.Active, CoordinationStatus.NotEnrolled -> null
    is CoordinationStatus.Pending -> ReachabilityBanner(
        message = "Your vault is waiting for your box.",
        detail = if (status.refused) "Your box refused this phone. Its keeper should check the enrolment."
            else "Changes to your vault wait until your box confirms them.",
        action = "Open Restore witness",
    )
    is CoordinationStatus.Fenced -> ReachabilityBanner(
        message = "Your vault on this phone is fenced.",
        detail = "It looks restored or copied, so it will not be used. Your box's keeper must retire it.",
        action = "Open Restore witness",
    )
}
