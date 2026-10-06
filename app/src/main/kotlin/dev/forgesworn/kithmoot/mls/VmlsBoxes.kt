package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.ParticipantSigner
import kotlinx.coroutines.flow.StateFlow

/** What a persona still lacks before it can hold a VMLS room on this phone, in the order to fix it. */
enum class VmlsNeed {
    /** Nobody is signed in. */
    SIGN_IN,
    /** The vault is not confirmed by its restore witness (P3-03b-2). */
    WITNESS,
    /** No rendezvous key from the signer (Heartwood provisions one). */
    RENDEZVOUS,
}

/**
 * One box a persona reaches for VMLS rooms (P3-03b-3 decision 16): its own
 * ordinary route, shared by every VMLS room there. [vmls] is whether the
 * box last answered with VMLS and its installation for this phone's device;
 * null before it was asked.
 */
data class VmlsBoxView(val box: String, val name: String, val vmls: Boolean?, val rooms: Int)

/** The debug VMLS boxes page: the signed-in persona, what it lacks, its MLS device and its boxes. */
data class VmlsBoxesState(
    val persona: String? = null,
    val needs: List<VmlsNeed> = emptyList(),
    val device: String? = null,
    val boxes: List<VmlsBoxView> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * VMLS rooms' runtime as the app's screens reach it (P3-03b-3), with no
 * engine type in sight: debug builds have one, release builds none
 * (`vmlsBoxes()` answers null there, as `restoreWitness()` does).
 */
interface VmlsBoxes {
    val state: StateFlow<VmlsBoxesState>

    /**
     * A vault consent ask waiting on the person (§6.2): once per persona,
     * device, box and method, then retained. Answer it with [answer].
     */
    val consent: StateFlow<ConsentScope?>

    fun answer(scope: ConsentScope, decision: ConsentDecision)

    /** The page opened for [persona], or none signed in. */
    fun open(persona: String?)

    /**
     * Pairs [signer]'s persona with the box whose pairing [code] it shows,
     * enrols the persona's MLS device if it has none, grants that device at
     * the box, and checks the box answers with VMLS. Only the box's owner's
     * grant is taken.
     */
    fun pair(signer: ParticipantSigner, code: String)

    /** Asks [box] again whether it answers with VMLS. */
    fun check(persona: String, box: String)

    /** Withdraws the device's grant at [box] and forgets its route; refused while a room uses it. */
    fun forget(signer: ParticipantSigner, box: String)

    /** One pass over [persona]'s VMLS rooms while the app is in the foreground. */
    suspend fun foregroundRounds(persona: String?)
}
