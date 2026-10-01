package dev.forgesworn.kithmoot.notifications

import android.content.Context
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The name an incoming call shows for its caller, the way a phone shows a
 * contact's name rather than their number.
 *
 * In order: the name on this phone's contact card for them (the person's
 * own choice, see `storage/ContactBook.kt`), then the name a room last
 * showed for them (their public profile name, or the name they gave the
 * room). Only a caller this phone has never seen named falls back to the
 * short key.
 *
 * The room names arrived encrypted inside rooms, so they are kept the same
 * way the rooms are: device-encrypted, no-backup, on this phone only, and
 * never sent anywhere. Bounded, oldest dropped first, and only written when
 * a name actually changes.
 */
object CallerNames {
    private const val LIMIT = 256
    private const val MAX_BYTES = 64 * 1024
    private val seen = LinkedHashMap<String, String>()
    private var storage: EncryptedRoomStorage? = null

    /** What a live room shows for its people, keyed by participant. Call off the main thread. */
    @Synchronized
    fun remember(context: Context, names: Map<String, String>) {
        load(context)
        var changed = false
        for ((participant, name) in names) {
            if (name.isBlank() || seen[participant] == name) continue
            seen.remove(participant)
            seen[participant] = name
            changed = true
        }
        if (!changed) return
        while (seen.size > LIMIT) seen.remove(seen.keys.first())
        runCatching {
            storage?.write(JsonObject(seen.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8))
        }
    }

    /** The caller's name, or `caller` unchanged when there is none (see `callerLabel`). Call off the main thread. */
    fun label(context: Context, caller: String): String {
        val card = runCatching {
            (context.applicationContext as KithMootApplication).contacts.list().firstOrNull { it.p == caller }?.name
        }.getOrNull()
        return card?.takeIf { it.isNotBlank() } ?: remembered(context, caller) ?: caller
    }

    /** Saved rooms reset: forget every name with them. */
    @Synchronized
    fun reset(context: Context) {
        load(context)
        seen.clear()
        runCatching { storage?.reset() }
    }

    @Synchronized
    private fun remembered(context: Context, participant: String): String? {
        load(context)
        return seen[participant]
    }

    private fun load(context: Context) {
        if (storage != null) return
        val store = EncryptedRoomStorage(context, "kithmoot.caller-names.v1", MAX_BYTES)
        storage = store
        runCatching {
            val bytes = store.read() ?: return
            for ((participant, name) in Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject) {
                seen[participant] = name.jsonPrimitive.content
            }
        }
    }
}
