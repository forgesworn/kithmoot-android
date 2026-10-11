package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.RosterEntry

/** Published under RoomSession's acceptance lock before any UI flow. Callbacks
 * read this immutable snapshot without acquiring the session lock. Heartbeats
 * keep grants stable; removal, mute, role loss and capability changes do not. */
internal class RecordingRosterAuthority(private val participant: String, private val device: String) {
    internal data class Key(val device: String, val role: String, val track: String)
    internal data class Meaning(val participant: String, val call: String, val profile: Int?, val callProfile: Int?)
    internal data class Grant(val key: Key, val meaning: Meaning, val revision: Long)
    private data class Snapshot(val grants: Map<Key, Grant>, val mic: String?, val monitor: String?,
        val micRevision: Long, val monitorRevision: Long)
    @Volatile private var snapshot = Snapshot(emptyMap(), null, null, 0, 0)
    private var revision = 0L

    fun update(accepted: Collection<RosterEntry>) {
        val previous = snapshot
        val present = accepted.filter { !it.left }
        val own = present.filter { it.participant == participant }
        val mic = RoleArbiter.holder(own, Roles.MIC)
        val monitor = RoleArbiter.holder(own, Roles.MONITOR)
        val microphones = present.groupBy { it.participant }.mapValues { (_, entries) -> RoleArbiter.holder(entries, Roles.MIC) }
        val grants = buildMap {
            for (entry in present) {
                val call = entry.call?.id ?: continue
                val profile = entry.recordingProfile
                for (track in entry.tracks) {
                    if (track.muted == true || (track.role == Roles.MIC && microphones[entry.participant] != entry.device)) continue
                    val key = Key(entry.device, track.role, track.trackId)
                    val meaning = Meaning(entry.participant, call, profile, entry.callProfile)
                    val old = previous.grants[key]
                    put(key, if (old != null && old.meaning == meaning) old else Grant(key, meaning, ++revision))
                }
            }
        }
        snapshot = Snapshot(grants, mic, monitor,
            if (mic == previous.mic) previous.micRevision else ++revision,
            if (monitor == previous.monitor) previous.monitorRevision else ++revision)
    }

    fun source(call: String, device: String, advertisedTrack: String?, declaredRole: String?): Grant? =
        snapshot.grants.values.singleOrNull {
            it.key.device == device && it.meaning.call == call &&
                (if (declaredRole != null) it.meaning.callProfile == CALL_PROFILE_2 && it.key.role == declaredRole
                 else it.key.track == advertisedTrack)
        }

    fun localMicrophone(): Long? = snapshot.let { if (it.mic == device) it.micRevision else null }
    fun localMonitor(): Long? = snapshot.let { if (it.monitor == null || it.monitor == device) it.monitorRevision else null }
}
