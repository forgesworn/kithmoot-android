package dev.forgesworn.kithmoot.storage

import kotlinx.coroutines.CancellationException

/** The room a wipe forgets, and the identity its per-device stores are keyed by. */
class RoomWipeTarget(val roomId: String, val participant: String, val devicePubkey: String) {
    override fun toString(): String = "RoomWipeTarget(${roomId.take(8)})"
}

/**
 * Every place this phone keeps something naming a room. Forgetting a room by
 * hand removes the saved room, its epochs and members, its work and its
 * outbox; a room that self-destructs must also leave nothing in the
 * background service's stores, the tray, or the NIP-77 archives. Each is a
 * named step, so a test can hold the list to account and a new store has to
 * be added here to be wiped.
 */
enum class RoomWipeStep {
    /** Messages kept on this phone and not yet sent (`PendingChatVault`). */
    PENDING_OUTBOX,
    /** Scoped file-host deletion permissions; room identifiers are removed immediately. */
    UPLOADED_FILES,
    /** The room's work, assignments and their decisions (`AssignmentVault`). */
    ASSIGNMENTS,
    /** What the background service received while the room was closed (`BackgroundInboxVault`). */
    BACKGROUND_INBOX,
    /** Which device was which participant, for naming a call bell (`BackgroundParticipantCache`). */
    PARTICIPANT_CACHE,
    /** Every notification posted under the room's id: messages, calls, the heads-up. */
    NOTIFICATIONS,
    /** Outer events kept for a NIP-77 custody offer (`Nip77OfferArchive`). */
    NIP77_OFFERS,
    /** Outer-event metadata kept for a NIP-77 comparison (`Nip77EventIndex`). */
    NIP77_INDEX,
    /** How a call in the room rings (`CallRingSettings`). */
    CALL_RING_SETTING,
    /** Original root notices and their retained retry debt. */
    NATIVE_COURIER,
    /** The sole native root signer and authority transaction state. */
    NATIVE_AUTHORITY,
    /** The room's epoch keys and their history (`EpochVault`). */
    EPOCHS,
    /** Who the room knows, for its epoch desks (`RoomMembers`). */
    MEMBERS,
    /** The saved room itself, with its secret and this device's key. Last: the steps before it may read it. */
    SAVED_ROOM,
}

/**
 * Runs every [RoomWipeStep] for one room, in order, each on its own: a store
 * that cannot be cleared does not keep the others from being cleared. Refuses
 * to be built without a way to clear every one.
 */
class RoomWipe(private val steps: Map<RoomWipeStep, suspend (RoomWipeTarget) -> Unit>) {
    init {
        val missing = RoomWipeStep.entries.toSet() - steps.keys
        require(missing.isEmpty()) { "A room wipe must clear every store; missing $missing" }
    }

    /** The steps that could not finish, empty when every one did. [only] runs a few again. */
    suspend fun run(target: RoomWipeTarget, only: Set<RoomWipeStep> = RoomWipeStep.entries.toSet()): List<RoomWipeStep> {
        val left = mutableListOf<RoomWipeStep>()
        for (step in RoomWipeStep.entries.filter { it in only }) {
            // Keep the public alias reference until both independent native stores are gone.
            if (step == RoomWipeStep.SAVED_ROOM && left.any { it == RoomWipeStep.NATIVE_COURIER || it == RoomWipeStep.NATIVE_AUTHORITY }) {
                left += step; continue
            }
            try { steps.getValue(step)(target) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { left += step }
        }
        return left
    }
}
