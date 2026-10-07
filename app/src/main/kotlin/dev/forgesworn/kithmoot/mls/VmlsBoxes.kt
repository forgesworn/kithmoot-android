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

/** Where a VMLS room stands, as its screen says it (P3-03b-3 decisions 22 and 23). */
enum class VmlsRoomState {
    READY,
    /** A guest waiting for the keeper to add it. */
    JOINING,
    SENDING,
    RETRYING,
    /** "Checking with the box". */
    CHECKING,
    /** Sending stopped: a recovery, an unknown event or a possible key compromise. [VmlsRoomView.reason] says which. */
    STOPPED,
    /** "You were removed": read-only for good. */
    REMOVED,
    /** "The invitation lapsed": read-only for good. */
    LAPSED,
    /** Leaving or closing: forgotten once that is done. */
    CLOSING,
}

/** A member as the room shows it; [invited] until its first Update confirms it. */
data class VmlsMemberView(val leaf: String, val identity: String, val device: String, val invited: Boolean)

/** One message while the app ran: not kept (P3-05 decides history). */
data class VmlsMessageView(val mine: Boolean, val sender: String, val body: String, val at: Long)

/** A VMLS room as the room list and its screen show it. */
data class VmlsRoomView(
    val session: String,
    val name: String,
    val box: String,
    val boxName: String,
    val keeper: Boolean,
    val state: VmlsRoomState,
    /** Words for a stopped room's reason; null otherwise. */
    val reason: String?,
    val members: List<VmlsMemberView>,
    /** The keeper's link is live. */
    val invite: Boolean,
    val canSend: Boolean,
    val messages: List<VmlsMessageView>,
)

/** How a room is left from its menu. */
enum class VmlsRoomExit { CLOSE, FORGET, LEAVE }

/**
 * A keeper always closes, even a room that ended (it was removed, say): only a
 * close revokes the guests' grants at the box (D1 R1). A guest forgets a room
 * that ended and leaves one that has not.
 */
val VmlsRoomView.exit: VmlsRoomExit
    get() = when {
        keeper -> VmlsRoomExit.CLOSE
        state == VmlsRoomState.REMOVED || state == VmlsRoomState.LAPSED -> VmlsRoomExit.FORGET
        else -> VmlsRoomExit.LEAVE
    }

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
 * engine type in sight: every build has one.
 */
interface VmlsBoxes {
    val state: StateFlow<VmlsBoxesState>

    /**
     * A vault consent ask waiting on the person (§6.2): once per persona,
     * device, box and method, then retained. Answer it with [answer].
     */
    val consent: StateFlow<ConsentScope?>

    fun answer(scope: ConsentScope, decision: ConsentDecision)

    /** The name of [persona]'s paired [box], for the consent ask beside its short id; null when it is not paired or cannot be read. */
    suspend fun boxName(persona: String, box: String): String? = null

    /** The vault's session ended (sign-out or account switch): withdraw any ask still showing. */
    fun sessionEnded() {}

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

    /** The signed-in persona's VMLS rooms, beside its saved rooms (decision 21). */
    val rooms: StateFlow<List<VmlsRoomView>>

    /** The keeper's new room [name] on [box], which must answer with VMLS. */
    fun createRoom(persona: String, box: String, name: String)

    fun say(persona: String, session: String, text: String)

    /** A new link for the keeper's room, answered over [relays], to share; an earlier link is retired by it. */
    suspend fun inviteLink(persona: String, session: String, base: String, relays: List<String>): String

    /** Retires the keeper's link: no more prompts (decision 18). */
    fun retireInvite(persona: String, session: String)

    /** The keeper removes [leaf] from the room. */
    fun removeMember(persona: String, session: String, leaf: String)

    /** A guest leaves (decision 20): its session ends on this phone, and the keeper removes its leaf when it lapses. */
    fun leave(persona: String, session: String)

    /**
     * The keeper closes the room (decision 24): its link is retired, the
     * grants of guests in none of its other rooms on the box are revoked by
     * [signer], and its session ends. Calling it again retries a close a
     * revocation held up; with [force], it finishes without that revocation.
     */
    fun close(signer: ParticipantSigner, session: String, force: Boolean = false)

    /** Forgets a room that ended (removed, or its invitation lapsed). */
    fun forgetRoom(persona: String, session: String)

    /**
     * One pass over [persona]'s VMLS rooms while the app is in the foreground.
     * With the persona's [signer], a keeper's grants for devices removed from
     * its rooms are revoked at the box (D1 R2); without it they wait for the
     * next pass that has it, or for the room's close.
     */
    suspend fun foregroundRounds(persona: String?, signer: ParticipantSigner? = null)

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
