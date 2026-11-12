package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.node.NodeKnocks
import dev.forgesworn.kithmoot.ui.start.roomLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Turns knocks waiting while KithMoot is out of sight into [KnockNotices], once
 *  per request, and takes a room's notice down when nobody is waiting there. */
internal object KnockNoticeWatcher {
    @Volatile private var started = false

    @Synchronized fun start(application: KithMootApplication) {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val told = HashSet<String>()
            var rooms = emptySet<String>()
            NodeKnocks.all.collect { knocks ->
                val byRoom = knocks.groupBy { it.room }
                for (room in rooms - byRoom.keys) KnockNotices.cancel(application, room)
                rooms = byRoom.keys
                told.retainAll(knocks.map { it.request.id }.toSet())
                for ((room, waiting) in byRoom) {
                    val fresh = waiting.filter { it.request.id !in told }
                    told.addAll(waiting.map { it.request.id })
                    // On screen the card says it; a notice is for when nobody is looking.
                    if (fresh.isEmpty() || NodeKnocks.appVisible) continue
                    val name = runCatching { application.savedRooms.get(room)?.name }.getOrNull()
                    KnockNotices.post(application, room, roomLabel(name, room), fresh.last().request.name?.take(80) ?: "A guest", waiting.size)
                }
            }
        }
    }
}
