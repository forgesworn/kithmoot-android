package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.GrantReceiver
import dev.forgesworn.kithmoot.protocol.RoomStorageRecord
import dev.forgesworn.kithmoot.protocol.storageNodeServer
import dev.forgesworn.kithmoot.protocol.usableGroupBase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where a file sent in one room goes (S5b). The room's own storage list comes
 * first, above anything this device chose: a room that lists nodes keeps its
 * files there and nowhere else, and a room whose list is empty keeps them off.
 * Neither ever falls back to the device's server, public or own. Only a room
 * that has never named storage goes the way this device chose. Mirrors the
 * first two branches of `resolveFileDestination` in the web client.
 */
sealed interface RoomFiles {
    data class Group(val nodes: List<String>) : RoomFiles
    data object Withdrawn : RoomFiles
    data class Device(val destination: FileDestination) : RoomFiles
}

fun resolveRoomFiles(roomStorage: RoomStorageRecord?, device: () -> FileDestination): RoomFiles = when {
    roomStorage == null -> RoomFiles.Device(device())
    roomStorage.nodes.isEmpty() -> RoomFiles.Withdrawn
    else -> RoomFiles.Group(roomStorage.nodes)
}

/** The composer's and Room info's line for a listed room. */
const val GROUP_FILES_LINE = "Files in this room go to the group’s own storage."
/** What a room says when its keeper withdrew every node. */
const val GROUP_NO_STORAGE = "This group has no storage right now."
/** None of the room's nodes is one this app can reach (http on the keeper's machine, or onion). */
const val GROUP_STORAGE_OUT_OF_REACH = "The group’s storage is not somewhere this app can reach."
/** Send pressed while a file still waits for the group's storage. */
const val GROUP_FILE_STILL_WAITING = "A file is still waiting for the group’s storage. Send once it’s stored, or remove it."
/** A recording in a room that keeps its files on its own storage, which this app does not send recordings to yet. */
const val GROUP_ROOM_RECORDINGS = "Recordings can’t be shared in this room yet. It keeps its files on the group’s own storage."

/** Room info's line for a room that names its own storage, or null to say what the device chose. */
fun roomFilesLine(roomStorage: RoomStorageRecord?): String? = when {
    roomStorage == null -> null
    roomStorage.nodes.isEmpty() -> GROUP_NO_STORAGE
    else -> GROUP_FILES_LINE
}

/** A grant this close to its end, with no renewal, is worth telling the person about. */
const val GRANT_WARNING_SECONDS = 7L * 86_400

/** Whether this device holds an active grant at any of [nodes]. */
fun holdsAnyGrant(receiver: GrantReceiver, nodes: List<String>, now: Long): Boolean = synchronized(receiver) {
    nodes.any { base -> storageNodeServer(base)?.let { receiver.grantsFor(it, now).isNotEmpty() } == true }
}

/**
 * When the keeper has gone quiet: this device holds grants at one or more of
 * [nodes], and the newest at every one ends within a week. Returns when group
 * files stop working, unix seconds; null when any lasts longer or none is held.
 */
fun grantsEndingSoon(receiver: GrantReceiver, nodes: List<String>, now: Long): Long? = synchronized(receiver) {
    var latest = 0L
    var held = 0
    for (base in nodes) {
        val server = storageNodeServer(base) ?: continue
        val grants = receiver.grantsFor(server, now)
        if (grants.isEmpty()) continue
        held++
        val end = grants.maxOf { it.expiration }
        if (end - now > GRANT_WARNING_SECONDS) return@synchronized null
        latest = maxOf(latest, end)
    }
    if (held > 0) latest else null
}

fun grantsEndingWords(date: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val day = DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(Instant.ofEpochSecond(date).atZone(zone))
    return "The group’s keeper has not been online for a while. Group files stop working on $day unless it comes back."
}

/** The listed nodes this app can reach. */
fun reachableGroupNodes(nodes: List<String>): List<String> = nodes.filter(::usableGroupBase)

/** A file waiting for the group's storage: shown as a chip, sent once a node takes it. */
data class WaitingFile(val sha256: String, val name: String, val words: String, val final: Boolean = false)
