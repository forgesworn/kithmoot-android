package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * A persona's ordinary Link route to a box (P3-03b-3 decision 16), shared
 * by every VMLS room the persona keeps or joins there. Never the persona's
 * witness-only route. [boxName] is what the consent prompt names (decision 18).
 */
data class VmlsBoxRoute(val persona: String, val box: String, val routeId: String, val boxName: String) {
    init {
        require(ROOM_HEX64.matches(persona) && ROOM_HEX64.matches(box))
        require(ROUTE_ID.matches(routeId))
        require(boxName.isNotBlank() && boxName.length <= VmlsRoom.MAX_NAME && boxName.none { it.isISOControl() })
    }

    private companion object { val ROUTE_ID = Regex("[A-Za-z0-9._:-]{1,128}") }
}

/**
 * The VMLS rooms and box routes on this phone (decision 21): `SavedRoom` and its storage
 * are untouched, so existing rooms are unchanged. A room is stored as
 * [VmlsRoom]'s stored fields; its members, epoch and messages are not.
 * A closed or forgotten room is removed; its snapshot is the snapshot
 * store's to wipe. One store per storage: its lock is the instance's.
 */
class VmlsRoomStore(private val storage: RoomStorage) {
    @Synchronized fun rooms(): List<VmlsRoom> = read().rooms

    @Synchronized fun room(persona: String, session: String): VmlsRoom? =
        read().rooms.singleOrNull { it.persona == persona && it.session == session }

    /**
     * Stores [room]'s stored fields, replacing the room with its persona and
     * session: for a new room. A room already stored changes by [update] or
     * [saveDriven].
     */
    @Synchronized fun put(room: VmlsRoom) {
        val state = read()
        val others = state.rooms.filterNot { it.persona == room.persona && it.session == room.session }
        require(others.size < MAX_ROOMS) { "Too many VMLS rooms." }
        write(state.copy(rooms = others + room))
    }

    /**
     * The room as [change] leaves it, read and written under the store's
     * lock, or null when the room is not stored (nothing is written).
     */
    @Synchronized fun update(persona: String, session: String, change: (VmlsRoom) -> VmlsRoom): VmlsRoom? {
        val state = read()
        val stored = state.rooms.singleOrNull { it.persona == persona && it.session == session } ?: return null
        val next = change(stored)
        require(next.persona == persona && next.session == session && next.box == stored.box && next.role == stored.role)
        if (next == stored) return next
        write(state.copy(rooms = state.rooms.map { if (it === stored) next else it }))
        return next
    }

    /**
     * Stores the driver's fields of [room] (its name, whether joined, its
     * stop and its removal grace), keeping the invite and consent counts
     * the consent gate stores, so neither writer undoes the other. A room
     * forgotten meanwhile stays forgotten.
     */
    fun saveDriven(room: VmlsRoom): VmlsRoom? = update(room.persona, room.session) { stored ->
        room.copy(invite = stored.invite, asked = stored.asked, prompted = stored.prompted, closing = laterClosing(stored.closing, room.closing))
    }

    /** A page action's closing is never undone by the driver's older copy. */
    private fun laterClosing(stored: Closing?, driven: Closing?): Closing? = listOfNotNull(stored, driven).maxOrNull()

    @Synchronized fun forget(persona: String, session: String) {
        val state = read()
        write(state.copy(rooms = state.rooms.filterNot { it.persona == persona && it.session == session }))
    }

    @Synchronized fun routes(): List<VmlsBoxRoute> = read().routes

    @Synchronized fun route(persona: String, box: String): VmlsBoxRoute? =
        read().routes.singleOrNull { it.persona == persona && it.box == box }

    /** One route per persona per box: a new pairing replaces the old. */
    @Synchronized fun putRoute(route: VmlsBoxRoute) {
        val state = read()
        val others = state.routes.filterNot { it.persona == route.persona && it.box == route.box }
        require(others.size < MAX_ROUTES) { "Too many box routes." }
        write(state.copy(routes = others + route))
    }

    /** Refused while a room of the persona still uses the box. */
    @Synchronized fun forgetRoute(persona: String, box: String) {
        val state = read()
        require(state.rooms.none { it.persona == persona && it.box == box }) { "A VMLS room still uses this box." }
        write(state.copy(routes = state.routes.filterNot { it.persona == persona && it.box == box }))
    }

    private data class State(val rooms: List<VmlsRoom>, val routes: List<VmlsBoxRoute>)

    private fun read(): State = guarded {
        val bytes = storage.read() ?: return@guarded State(emptyList(), emptyList())
        try {
            require(bytes.size <= MAX_BYTES)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root.getValue("version").jsonPrimitive.content == "1")
            val rooms = root.getValue("rooms").jsonArray.map { roomOf(it.jsonObject) }
            val routes = root.getValue("routes").jsonArray.map { value ->
                val o = value.jsonObject
                VmlsBoxRoute(o.text("persona"), o.text("box"), o.text("route"), o.text("boxName"))
            }
            require(rooms.size <= MAX_ROOMS && rooms.distinctBy { "${it.persona}/${it.session}" }.size == rooms.size)
            require(routes.size <= MAX_ROUTES && routes.distinctBy { "${it.persona}/${it.box}" }.size == routes.size)
            State(rooms, routes)
        } finally { bytes.fill(0) }
    }

    private fun write(state: State) = guarded {
        val bytes = buildJsonObject {
            put("version", 1)
            put("rooms", buildJsonArray { state.rooms.forEach { add(jsonOf(it)) } })
            put("routes", buildJsonArray { state.routes.forEach { r -> add(buildJsonObject {
                put("persona", r.persona); put("box", r.box); put("route", r.routeId); put("boxName", r.boxName)
            }) } })
        }.toString().encodeToByteArray()
        try {
            require(bytes.size <= MAX_BYTES)
            storage.write(bytes)
        } finally { bytes.fill(0) }
    }

    private fun jsonOf(room: VmlsRoom) = buildJsonObject {
        put("persona", room.persona); put("session", room.session); put("name", room.name); put("box", room.box)
        put("role", room.role.name); put("joined", room.joined)
        room.stop?.let { put("stop", it.code) }
        room.invite?.let { put("invite", it) }
        put("asked", buildJsonArray { room.asked.sorted().forEach { add(JsonPrimitive(it)) } })
        put("prompted", buildJsonArray { room.prompted.forEach { add(JsonPrimitive(it)) } })
        put("grace", buildJsonObject { room.grace.toSortedMap().forEach { (leaf, at) -> put(leaf, at) } })
        put("removing", buildJsonArray { room.removing.sorted().forEach { add(JsonPrimitive(it)) } })
        room.closing?.let { put("closing", it.name) }
        if (room.evicting.isNotEmpty()) put("evicting", buildJsonArray { room.evicting.sorted().forEach { add(JsonPrimitive(it)) } })
    }

    private fun roomOf(o: JsonObject) = VmlsRoom(
        persona = o.text("persona"), session = o.text("session"), name = o.text("name"), box = o.text("box"),
        role = VmlsRole.valueOf(o.text("role")),
        joined = o.getValue("joined").jsonPrimitive.content.toBooleanStrict(),
        stop = o["stop"]?.jsonPrimitive?.content?.let(RoomStop::parse),
        invite = o["invite"]?.jsonPrimitive?.content,
        asked = o.getValue("asked").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        prompted = o.getValue("prompted").jsonArray.map { it.jsonPrimitive.long },
        grace = o.getValue("grace").jsonObject.mapValues { it.value.jsonPrimitive.long },
        removing = o.getValue("removing").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        // Absent in rooms stored before closing existed.
        closing = o["closing"]?.jsonPrimitive?.content?.let(Closing::valueOf),
        evicting = o["evicting"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
    )

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.also { require(it.isString) }.content

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }

    private companion object {
        const val MAX_ROOMS = 64
        const val MAX_ROUTES = 64
        const val MAX_BYTES = 1024 * 1024
    }
}
