package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.AccountRoomOwner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * A moved room's owner key, shared between the account's devices through its
 * encrypted room bookmark (see account/RoomBookmarks.kt and the web client's
 * app/src/room-owner-share.ts), so any of the person's devices can keep the
 * room, not only the one that made or moved it. The cost, chosen by the owner:
 * a signed-in device that is lost keeps that control.
 *
 * Only a moved room's custody key travels. A room made on this phone and kept
 * by its native keeper never leaves it, by that keeper's design.
 */
internal object NodeSharedOwners {
    /** The owner key and lists this phone holds for a moved room it keeps, for the account's bookmark. */
    fun export(store: NodeStore, participant: String, room: String): AccountRoomOwner? {
        val custody = store.custody(participant, room) ?: return null
        val root = custody.rootKey ?: return null
        return try {
            if (Schnorr.publicKeyHex(root) != custody.node.authority) return null
            val state = NodeKeeperState.read(custody.keeper, custody.node)
            AccountRoomOwner.of(root.toHex(), state.members, state.removed)
        } finally { root.fill(0) }
    }

    /** Take in a shared key for a moved room this phone is an admitted member of, when it is the
     * key the room's signed record names. A key already kept is never replaced. True when this
     * phone now keeps the room. */
    fun adopt(store: NodeStore, participant: String, room: String, owner: AccountRoomOwner): Boolean {
        val custody = store.custody(participant, room) ?: return false
        if (custody.phase != "admitted" || custody.wire["rootKey"] != null || custody.pendingClose != null) return false
        val key = owner.key.hexToBytes()
        try { if (Schnorr.publicKeyHex(key) != custody.node.authority) return false } finally { key.fill(0) }
        val removed = owner.removed.filter { it != participant }.distinct().sorted().take(NodeKeeperAdmissions.MAX_REMOVED)
        val members = (listOf(participant) + owner.members).distinct().filter { it !in removed }.sorted().take(NodeKeeperAdmissions.MAX_LEARNED)
        store.update(participant, room) { current ->
            if (current.wire["rootKey"] != null) current else current.changed {
                put("rootKey", owner.key)
                if (current.keeper == null) put("keeper", NodeKeeperState(members, removed, emptyList()).toJson())
            }
        }
        return true
    }

    /** Apply the members and removals another of the account's devices shared to the journal of
     * a moved room this phone keeps: a person removed there is not let back in here, and this
     * phone's own passes for them end. */
    suspend fun apply(store: NodeStore, participant: String, room: String, owner: AccountRoomOwner,
        allowed: () -> Boolean, post: suspend (GroupEvent, ParticipantSigner) -> Unit) {
        val custody = store.custody(participant, room) ?: return
        if (!allowed() || custody.wire["rootKey"] == null || custody.pendingClose != null || custody.pendingRekey != null) return
        val keeper = NodeKeeperAdmissions(custody.node, participant, store, post, allowed)
        keeper.learnMembers(owner.members.filter { it !in owner.removed })
        val known = keeper.state().members
        for (person in owner.removed) if (person != participant && person in known) {
            try { keeper.remove(person) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Its revocation is retried with the next change. */ }
        }
        keeper.refuse(owner.removed.filter { it != participant })
    }
}
