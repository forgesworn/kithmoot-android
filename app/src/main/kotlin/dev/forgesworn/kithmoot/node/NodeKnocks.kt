package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.NodeJoinRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Knocks waiting on every node room keeper in this process: the open room's,
 * the background service's and each closed room's. The room on screen lists
 * its own in its panel; a knock on any other room waits over whatever is on
 * screen ([dev.forgesworn.kithmoot.ui.AwayKnocks]) and, with the app out of
 * sight, as a notification ([dev.forgesworn.kithmoot.notifications.KnockNotices]).
 * In memory only, as each keeper's own list is.
 */
internal object NodeKnocks {
    /** [opensRoom] is true for a room closed here ([NodeClosedRoomKeepers]): a
     *  newcomer's device asks the open room's desk for the room's current key,
     *  so letting them in opens the room, whose own keeper then lets them in
     *  without asking again ([approve]). Any other keeper lets them in at once. */
    data class Knock(val room: String, val request: NodeJoinRequest, val opensRoom: Boolean, internal val inbox: NodeKeeperInbox)

    private val held = LinkedHashMap<NodeKeeperInbox, List<Knock>>()
    private val approved = HashMap<String, Pair<String, Long>>()
    /** Requests already let in or declined by some keeper here: a closed
     *  room's keeper that heard the same request never shows it again. */
    private val done = LinkedHashSet<String>()
    private val _all = MutableStateFlow<List<Knock>>(emptyList())
    val all: StateFlow<List<Knock>> = _all.asStateFlow()

    /** Whether KithMoot is on screen, so a knock needs no notification. */
    @Volatile var appVisible = false

    @Synchronized fun put(inbox: NodeKeeperInbox, knocks: List<Knock>) {
        if (knocks.isEmpty()) held.remove(inbox) else held[inbox] = knocks
        publish()
    }

    /** This request has its answer, from whichever keeper gave it. */
    @Synchronized fun settled(requestId: String) {
        done.add(requestId)
        while (done.size > 512) done.remove(done.first())
        publish()
    }

    private fun publish() {
        _all.value = held.values.flatten().filter { it.request.id !in done }.distinctBy { it.request.id }
    }

    /** Lets them in from a keeper whose room is open, here or in the background service. */
    suspend fun admit(knock: Knock) { if (!knock.opensRoom) knock.inbox.admit(knock.request.id) }

    /** Yes to a knock on a room closed here, said before the room opens. */
    @Synchronized fun approve(knock: Knock) {
        approved.entries.removeAll { it.value.second <= System.currentTimeMillis() / 1000 }
        approved[knock.request.id] = knock.room to knock.request.expiresAt
    }

    /** Whether the owner already said yes to this request for this room; once only. */
    @Synchronized fun takeApproval(room: String, requestId: String, now: Long): Boolean {
        val (approvedRoom, until) = approved[requestId] ?: return false
        if (approvedRoom != room || until <= now) return false
        approved.remove(requestId)
        return true
    }

    /** Declining sends nothing; the guest keeps waiting until their request ends. */
    suspend fun decline(knock: Knock) = knock.inbox.decline(knock.request.id)
}
