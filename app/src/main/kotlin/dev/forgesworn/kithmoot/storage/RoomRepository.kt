package dev.forgesworn.kithmoot.storage

import kotlinx.serialization.json.*

/** Implementations must commit a whole value or leave the previous one intact. */
interface RoomStorage {
    fun read(): ByteArray?
    fun write(value: ByteArray)
    fun reset()
}

class RoomStorageException(cause: Exception) : Exception("Saved rooms are unavailable", cause)

/** One instance per application. All read/modify/write operations share this lock. */
class RoomRepository(private val storage: RoomStorage) {
    @Synchronized fun list(): List<SavedRoomSummary> = read().map { it.summary() }.sortedByDescending { it.openedAt }
    @Synchronized fun get(id: String): SavedRoom? = read().firstOrNull { it.id == id }
    @Synchronized fun findInvitation(url: String): SavedRoom? {
        val invitation = dev.forgesworn.kithmoot.protocol.decodeInvitationUrl(url)?.invitation ?: return null
        return read().firstOrNull { it.invitation?.invitation == invitation }
    }
    @Synchronized fun save(room: SavedRoom) {
        val rooms = read().filterNot { it.id == room.id } + room
        if (rooms.size > 100) throw RoomRecoveryException("You have 100 saved rooms. Forget one before adding another.")
        write(rooms)
    }
    @Synchronized fun update(id: String, change: (SavedRoom) -> SavedRoom): SavedRoom? {
        val rooms = read()
        val existing = rooms.firstOrNull { it.id == id } ?: return null
        val next = change(existing)
        require(next.id == id)
        write(rooms.map { if (it.id == id) next else it })
        return next
    }
    @Synchronized fun forget(id: String) = write(read().filterNot { it.id == id })
    /** Only used after an explicit destructive confirmation in the UI. */
    @Synchronized fun reset() = guarded { storage.reset() }

    private fun read(): List<SavedRoom> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        try {
            require(bytes.size <= 4 * 1024 * 1024)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root.getValue("version").jsonPrimitive.int == 1)
            val rooms = root.getValue("rooms").jsonArray.map { SavedRoom.decode(it.jsonObject) }
            require(rooms.size <= 100 && rooms.distinctBy { it.id }.size == rooms.size)
            rooms
        } finally { bytes.fill(0) }
    }

    private fun write(rooms: List<SavedRoom>) = guarded {
        val bytes = buildJsonObject {
            put("version", 1)
            put("rooms", JsonArray(rooms.map { it.json }))
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= 4 * 1024 * 1024)
            storage.write(bytes)
        } finally { bytes.fill(0) }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }
}
