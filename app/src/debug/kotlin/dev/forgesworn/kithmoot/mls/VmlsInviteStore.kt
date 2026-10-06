package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A join the keeper admitted (P3-03b-3 decision 17) and has not added yet:
 * the guest's device was granted and the answer sent, and the guest's
 * capability is awaited at the introduction mailbox for the guest's
 * [rendezvous] key at [counter], until [deadline] (seconds).
 */
data class VmlsPendingJoin(
    val requestId: String,
    val guest: String,
    val device: String,
    val rendezvous: String,
    val counter: Long,
    val deadline: Long,
) {
    init {
        require(listOf(requestId, guest, device, rendezvous).all(ROOM_HEX64::matches))
        require(counter >= 0 && deadline > 0)
    }
}

/**
 * A keeper's live link for one room: its bearer and its own key (both
 * secret: whoever holds them answers for the link), the relays it is
 * answered on, and the joins it admitted. Its invitation id is the room's
 * [VmlsRoom.invite].
 */
class VmlsLink(
    val persona: String,
    val session: String,
    val bearer: String,
    val key: String,
    val relays: List<String>,
    val joins: List<VmlsPendingJoin> = emptyList(),
) {
    init {
        require(listOf(persona, session, bearer, key).all(ROOM_HEX64::matches))
        require(relays.size <= MAX_RELAYS && relays.all { it.length <= MAX_RELAY_CHARS })
        require(joins.size <= MAX_JOINS && joins.map { it.requestId }.toSet().size == joins.size)
    }

    fun with(joins: List<VmlsPendingJoin>) = VmlsLink(persona, session, bearer, key, relays, joins)

    override fun toString(): String = "VmlsLink(${session.take(8)}…, joins=${joins.size})"

    companion object {
        const val MAX_JOINS = 16
        private const val MAX_RELAYS = 16
        private const val MAX_RELAY_CHARS = 512
    }
}

/**
 * The keepers' links (P3-03b-3), in their own encrypted store in the debug
 * source set, apart from the room store, since they hold the link's keys.
 * One store per storage: its lock is the instance's.
 */
class VmlsInviteStore(private val storage: RoomStorage) {
    @Synchronized fun links(): List<VmlsLink> = read()

    @Synchronized fun link(persona: String, session: String): VmlsLink? = read().singleOrNull { it.persona == persona && it.session == session }

    /** Stores [link] in place of the room's earlier one, if any. */
    @Synchronized fun put(link: VmlsLink) {
        val links = read().filterNot { it.persona == link.persona && it.session == link.session }
        require(links.size < MAX_LINKS) { "Too many live links." }
        write(links + link)
    }

    /** Changes the room's link under the lock; null when it has none. */
    @Synchronized fun update(persona: String, session: String, change: (VmlsLink) -> VmlsLink): VmlsLink? {
        val links = read()
        val stored = links.singleOrNull { it.persona == persona && it.session == session } ?: return null
        val next = change(stored)
        require(next.persona == persona && next.session == session && next.key == stored.key)
        write(links.map { if (it === stored) next else it })
        return next
    }

    @Synchronized fun forget(persona: String, session: String) = write(read().filterNot { it.persona == persona && it.session == session })

    private fun read(): List<VmlsLink> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        require(bytes.size <= MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(root.getValue("version").jsonPrimitive.content == "1")
        (root.getValue("links") as JsonArray).map { linkOf(it.jsonObject) }
    }

    private fun write(links: List<VmlsLink>) = guarded {
        val bytes = buildJsonObject {
            put("version", "1")
            putJsonArray("links") { links.forEach { add(jsonOf(it)) } }
        }.toString().encodeToByteArray()
        require(bytes.size <= MAX_BYTES)
        storage.write(bytes)
    }

    private fun jsonOf(link: VmlsLink) = buildJsonObject {
        put("persona", link.persona)
        put("session", link.session)
        put("bearer", link.bearer)
        put("key", link.key)
        put("relays", buildJsonArray { link.relays.forEach { add(JsonPrimitive(it)) } })
        put("joins", buildJsonArray {
            link.joins.forEach { join ->
                add(buildJsonObject {
                    put("request", join.requestId); put("guest", join.guest); put("device", join.device)
                    put("rz", join.rendezvous); put("counter", join.counter); put("deadline", join.deadline)
                })
            }
        })
    }

    private fun linkOf(o: JsonObject) = VmlsLink(
        o.text("persona"), o.text("session"), o.text("bearer"), o.text("key"),
        (o.getValue("relays") as JsonArray).map { it.jsonPrimitive.also { p -> require(p.isString) }.content },
        (o.getValue("joins") as JsonArray).map { e ->
            val j = e.jsonObject
            VmlsPendingJoin(j.text("request"), j.text("guest"), j.text("device"), j.text("rz"), j.number("counter"), j.number("deadline"))
        },
    )

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.number(name: String): Long = getValue(name).jsonPrimitive.also { require(!it.isString) }.long

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }

    private companion object {
        const val MAX_LINKS = 64
        const val MAX_BYTES = 256 * 1024
    }
}
