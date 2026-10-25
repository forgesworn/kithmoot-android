package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.protocol.PublicCallRelayAdvert
import dev.forgesworn.kithmoot.protocol.PublicRelayWatermark
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The relay that last worked, remembered on this device only. */
internal data class PublicCallRelayLastGood(val key: String, val host: String)

/**
 * Everything kept for finding public call relays while the switch is on: the
 * last good relay and the replay watermarks with the id of the event that set
 * each. Adverts and logins are never kept. The state exists only while the
 * switch is on, so there is no field for the switch itself.
 */
internal data class PublicCallRelayState(
    val lastGood: PublicCallRelayLastGood? = null,
    val watermarks: List<PublicRelayWatermark> = emptyList(),
)

internal object PublicCallRelayStateCodec {
    /** 1024 watermarks come to about 175 KB. */
    const val MAX_BYTES = 262_144
    private const val VERSION = 1
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val DIGITS = Regex("[0-9]{1,16}")

    fun encode(state: PublicCallRelayState): ByteArray = buildJsonObject {
        put("v", VERSION)
        put("on", true)
        state.lastGood?.let { good ->
            put("lastGood", buildJsonObject { put("key", good.key); put("host", good.host) })
        }
        put("watermarks", buildJsonArray {
            for (mark in state.watermarks.take(PublicCallRelayAdvert.MAX_TRACKED)) {
                add(buildJsonObject { put("key", mark.key); put("createdAt", mark.createdAt); put("id", mark.id) })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    /** The saved state, or null for anything that is not a version 1 file with
     *  the switch on. Entries that do not validate are dropped, not repaired. */
    fun decode(bytes: ByteArray): PublicCallRelayState? {
        if (bytes.size > MAX_BYTES) return null
        return try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: return null
            val version = root["v"] as? JsonPrimitive
            val on = root["on"] as? JsonPrimitive
            if (version == null || version.isString || version.content != VERSION.toString()) return null
            if (on == null || on.isString || on.content != "true") return null
            val good = root["lastGood"] as? JsonObject
            val goodKey = text(good?.get("key"))
            val goodHost = text(good?.get("host"))
            val lastGood = if (goodKey != null && goodHost != null && HEX64.matches(goodKey) && PublicCallRelayAdvert.hostAllowed(goodHost)) {
                PublicCallRelayLastGood(goodKey, goodHost)
            } else null
            val marks = (root["watermarks"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val mark = entry as? JsonObject ?: return@mapNotNull null
                val key = text(mark["key"])
                val id = text(mark["id"])
                val created = (mark["createdAt"] as? JsonPrimitive)?.takeIf { !it.isString && DIGITS.matches(it.content) }?.content?.toLongOrNull()
                if (key == null || id == null || created == null || !HEX64.matches(key) || !HEX64.matches(id)) null
                else PublicRelayWatermark(key, created, id)
            }.take(PublicCallRelayAdvert.MAX_TRACKED)
            PublicCallRelayState(lastGood, marks)
        } catch (_: Exception) {
            null
        }
    }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Keystore-encrypted, excluded from backups, in a file of its own: not
 * [CallRelayStore], and never holding a login. A present file means the
 * switch is on; switching off clears it. Call only on an IO dispatcher.
 */
internal class PublicCallRelayStore(private val storage: RoomStorage) {
    constructor(context: Context) : this(EncryptedRoomStorage(context, FILE, PublicCallRelayStateCodec.MAX_BYTES))

    /** The saved state, or null when the switch is off, or the file is missing,
     *  unreadable or not ours: all of which mean off. */
    fun read(): PublicCallRelayState? = try {
        storage.read()?.let(PublicCallRelayStateCodec::decode)
    } catch (_: Exception) {
        null
    }

    /** Turns the switch on, or saves the state while it is on. Throws if it could not be saved. */
    fun write(state: PublicCallRelayState) = storage.write(PublicCallRelayStateCodec.encode(state))

    /** Switch off: forget the last good relay and every watermark. Throws if it could not be cleared. */
    fun clear() = storage.reset()

    companion object {
        const val FILE = "kithmoot.public-call-relays.v1"
    }
}
