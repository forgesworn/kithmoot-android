package dev.forgesworn.kithmoot.storage

import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Implementations must commit a whole value or leave the previous one intact. */
interface RoomStorage {
    fun read(): ByteArray?
    fun write(value: ByteArray)
    fun reset()
}

class RoomStorageException(cause: Exception) : Exception("Saved rooms are unavailable", cause)

/** One instance per application. All read/modify/write operations share this lock. */
class RoomRepository(private val storage: RoomStorage) {
    private val _revision = MutableStateFlow(0L)
    /** Successful local writes, with no room identifiers or keys in the notification. */
    val revision = _revision.asStateFlow()
    @Synchronized fun list(): List<SavedRoomSummary> = read().map { it.summary() }.sortedByDescending { it.openedAt }
    @Synchronized fun get(id: String): SavedRoom? = read().firstOrNull { it.id == id }
    @Synchronized fun findInvitation(url: String): SavedRoom? {
        val invitation = dev.forgesworn.kithmoot.protocol.decodeInvitationUrl(url)?.invitation ?: return null
        return read().firstOrNull { it.invitation?.invitation == invitation }
    }
    @Synchronized fun save(room: SavedRoom) {
        val previous = read()
        previous.firstOrNull { it.id == room.id }?.let { checkReplacement(it, room) }
        val rooms = previous.filterNot { it.id == room.id } + room
        if (rooms.size > 100) throw RoomRecoveryException("You have 100 saved rooms. Forget one before adding another.")
        write(rooms)
    }
    /** A fresh admission cannot overwrite a room saved while it was waiting. */
    @Synchronized fun saveNew(room: SavedRoom) {
        check(get(room.id) == null) { "This room is already saved. Open it from your rooms." }
        save(room)
    }
    /** Roll back only the new local identity owned by this failed entry. */
    @Synchronized fun forgetIfIdentity(id: String, participant: String, device: String) {
        val room = get(id) ?: return
        if (room.participant == participant && room.devicePubkey == device) forget(id)
    }
    @Synchronized fun update(id: String, change: (SavedRoom) -> SavedRoom): SavedRoom? {
        val rooms = read()
        val existing = rooms.firstOrNull { it.id == id } ?: return null
        val next = change(existing)
        require(next.id == id)
        checkReplacement(existing, next)
        write(rooms.map { if (it.id == id) next else it })
        return next
    }
    @Synchronized fun forget(id: String) = write(read().filterNot { it.id == id })

    /** Existing monitor spans exact current identity inspection and each source
     * mutation. Callers never supply a stale whole-room replacement. */
    @Synchronized internal fun <T> withNativeIndex(source: dev.forgesworn.kithmoot.epoch.NativeKeeperJournal,
        action: (SavedRoom) -> T): T {
        val current = requireNotNull(read().singleOrNull { it.id == source.binding.room }) { "Native room index is missing" }
        return action(current)
    }
    @Synchronized internal fun installNativeReplacement(source: dev.forgesworn.kithmoot.epoch.NativeKeeperJournal): SavedRoom {
        val rooms = read()
        val current = requireNotNull(rooms.singleOrNull { it.id == source.binding.room }) { "Native room index is missing" }
        val next = source.replacementIndexValue(current)
        if (next.json != current.json) write(rooms.map { if (it.id == current.id) next else it })
        val actual = requireNotNull(read().singleOrNull { it.id == current.id })
        source.requireReplacementIndex(actual)
        return actual
    }
    /** Only used after an explicit destructive confirmation in the UI. */
    @Synchronized fun reset() = guarded { storage.reset(); _revision.update { it + 1 } }

    private fun read(): List<SavedRoom> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        try {
            require(bytes.size <= 4 * 1024 * 1024)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val version = root.getValue("version").jsonPrimitive
            require(!version.isString && version.intOrNull in setOf(1, 2))
            val rooms = root.getValue("rooms").jsonArray.map { SavedRoom.decode(it.jsonObject) }
            require((version.intOrNull == 2) == rooms.any { it.nativeAuthority != null }) {
                "Native authority references require saved-room repository version 2"
            }
            require(rooms.size <= 100 && rooms.distinctBy { it.id }.size == rooms.size)
            rooms
        } finally { bytes.fill(0) }
    }

    private fun write(rooms: List<SavedRoom>) = guarded {
        val bytes = buildJsonObject {
            put("version", if (rooms.any { it.nativeAuthority != null }) 2 else 1)
            put("rooms", JsonArray(rooms.map { it.json }))
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= 4 * 1024 * 1024)
            storage.write(bytes)
            _revision.update { it + 1 }
        } finally { bytes.fill(0) }
    }

    private fun checkReplacement(previous: SavedRoom, next: SavedRoom) {
        previous.nativeAuthority?.let {
            require(next.nativeAuthority?.pin == it.pin && next.json["nativeAuthority"] == previous.json["nativeAuthority"]) {
                "Saving cannot remove or replace this room's native authority"
            }
        }
        if (next.nativeAuthority != null) require("host" !in previous.json) {
            "Legacy hosting requires explicit authority transfer"
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }
}
