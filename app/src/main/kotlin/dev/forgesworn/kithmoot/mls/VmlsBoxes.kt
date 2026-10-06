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

/**
 * A guest's request to join a keeper's room, as its prompt shows it
 * (P3-03b-3 decision 18): the room, the box it lives on, and the guest's
 * persona and MLS device. Answer it with [VmlsBoxes.admit].
 */
data class VmlsJoinAsk(
    val persona: String,
    val session: String,
    val room: String,
    val box: String,
    val boxName: String,
    val guest: String,
    val device: String,
    val requestId: String,
)

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

    /** The Link routes VMLS boxes use: the app's sweep of routes no room consented to keeps them. */
    fun routeIds(): Set<String>

    /** One pass over [persona]'s VMLS rooms while the app is in the foreground. */
    suspend fun foregroundRounds(persona: String?)

    /**
     * A join request waiting on the keeper (decision 18): one at a time per
     * room, at most five an hour, a device asked about once per link.
     */
    val joinAsk: StateFlow<VmlsJoinAsk?>

    /**
     * The keeper's answer to [ask]. Approved, [signer] grants the guest's
     * device at the box and the guest is answered; refused, it is told so.
     */
    fun admit(signer: ParticipantSigner, ask: VmlsJoinAsk, approve: Boolean)

    /**
     * Asks to join the VMLS room behind [url] as [signer]'s persona: this
     * phone pairs with the link's box by the [code] it shows (unless already
     * paired there), and waits up to ten minutes for the keeper's answer.
     */
    fun join(signer: ParticipantSigner, url: String, code: String)

    /**
     * Listens for join requests over [persona]'s live links until cancelled,
     * while the app is in the foreground: both phones are online for the
     * exchange (decision 17). Nothing is answered without the keeper.
     */
    suspend fun serveInvites(persona: String?)
}
